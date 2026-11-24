package com.foodsafety.exchange.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.config.ExchangeProperties;
import com.foodsafety.exchange.correlation.Correlator;
import com.foodsafety.exchange.correlation.Correlator.Decision;
import com.foodsafety.exchange.correlation.ReportChainState;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.EventKeys;
import com.foodsafety.exchange.event.EventLedger;
import com.foodsafety.exchange.event.EventType;
import com.foodsafety.exchange.projection.Projector;
import com.foodsafety.exchange.spec.AttachmentDecl;
import com.foodsafety.exchange.spec.MessageParser;
import com.foodsafety.exchange.spec.ParseException;
import com.foodsafety.exchange.spec.ParsedEntry;
import com.foodsafety.exchange.spec.ParserRegistry;
import com.foodsafety.exchange.spec.SpecVersion;
import com.foodsafety.exchange.util.Digests;
import com.foodsafety.exchange.validation.BusinessValidator;
import com.foodsafety.exchange.validation.BusinessValidator.Issue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入站处理流水线：
 * 批次定规范 -> 摘要/签名 -> 不可变入站库 -> 按容器固定规范解析 -> 业务校验 -> 身份关联（修订状态机）。
 * 全程只追加事件账，身份键不混用，不按相似内容合并样品。
 */
@Service
public class InboundService {

  private final InboundEnvelopeRepository envelopeRepo;
  private final BatchRegistryRepository batchRepo;
  private final EventLedger eventLedger;
  private final ParserRegistry parserRegistry;
  private final BusinessValidator validator;
  private final Correlator correlator;
  private final Projector projector;
  private final ExchangeProperties properties;
  private final ObjectMapper objectMapper;

  public InboundService(InboundEnvelopeRepository envelopeRepo, BatchRegistryRepository batchRepo,
      EventLedger eventLedger, ParserRegistry parserRegistry, BusinessValidator validator,
      Correlator correlator, Projector projector, ExchangeProperties properties,
      ObjectMapper objectMapper) {
    this.envelopeRepo = envelopeRepo;
    this.batchRepo = batchRepo;
    this.eventLedger = eventLedger;
    this.parserRegistry = parserRegistry;
    this.validator = validator;
    this.correlator = correlator;
    this.projector = projector;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public record EntryResult(String sampleKey, String reportNo, Integer revisionNo,
      String status, String reason, Long eventSeq) {}

  public record IngestResult(String containerId, String batchId, String specVersion,
      String sha256, boolean signatureValid, boolean retransmitted, boolean conflict,
      String containerStatus, List<EntryResult> entries) {}

  @Transactional
  public IngestResult ingest(String containerId, String batchId, String sender, String signature,
      String attachmentsJson, byte[] body) {
    BatchRegistry batch = batchRepo.findById(batchId)
        .orElseThrow(() -> ApiException.badRequest("UNKNOWN_BATCH", "未知发送批次: " + batchId));
    String secret = properties.senders().get(sender);
    if (secret == null) {
      throw ApiException.badRequest("UNKNOWN_SENDER", "未知发送方: " + sender);
    }
    List<AttachmentDecl> manifest = parseManifest(attachmentsJson);
    String digest = Digests.sha256Hex(body);
    boolean signatureValid = Digests.constantTimeEquals(
        Digests.hmacSha256Hex(secret, body), signature == null ? "" : signature.trim());
    String specVersion = batch.getSpecVersion();
    List<EntryResult> entries = new ArrayList<>();

    // 1) 不可变入站库：相同容器号+相同摘要不重复写入；相同容器号不同摘要记冲突
    InboundEnvelope existing = envelopeRepo.findFirstByContainerIdOrderById(containerId).orElse(null);
    boolean retransmitted = false;
    if (existing != null) {
      if (!existing.getPayloadSha256().equals(digest)) {
        eventLedger.append(EventType.CONTAINER_CONFLICT, EventKeys.container(containerId),
            Map.of("existingDigest", existing.getPayloadSha256(), "incomingDigest", digest));
        return new IngestResult(containerId, batchId, specVersion, digest, signatureValid,
            false, true, "CONTAINER_CONFLICT", entries);
      }
      retransmitted = true;
      eventLedger.append(EventType.CONTAINER_RETRANSMITTED, EventKeys.container(containerId),
          Map.of("digest", digest, "specVersion", specVersion));
    } else {
      envelopeRepo.save(new InboundEnvelope(containerId, batchId, sender, specVersion, digest,
          signature == null ? "" : signature.trim(), signatureValid,
          toJson(manifest), body));
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("containerId", containerId);
      payload.put("batchId", batchId);
      payload.put("sender", sender);
      payload.put("specVersion", specVersion);
      payload.put("digest", digest);
      payload.put("signatureValid", signatureValid);
      payload.put("attachments", manifest.stream().map(a -> Map.of("id", a.id(), "sha256", a.sha256())).toList());
      eventLedger.append(EventType.INBOUND_RECEIVED, EventKeys.container(containerId), payload);
    }

    // 2) 签名无效：解析失败，不进入业务处理
    if (!signatureValid) {
      eventLedger.append(EventType.PARSE_FAILED, EventKeys.container(containerId),
          Map.of("reason", "SIGNATURE_INVALID", "detail", "签名校验未通过"));
      return new IngestResult(containerId, batchId, specVersion, digest, false, retransmitted,
          false, "PARSE_FAILED", entries);
    }

    // 3) 按容器创建时固定的规范版本解析
    SpecVersion spec = SpecVersion.of(specVersion);
    MessageParser parser = spec == null ? null : parserRegistry.parserFor(spec);
    if (parser == null) {
      eventLedger.append(EventType.PARSE_FAILED, EventKeys.container(containerId),
          Map.of("reason", "UNKNOWN_SPEC_VERSION", "detail", "未知规范版本: " + specVersion));
      return new IngestResult(containerId, batchId, specVersion, digest, true, retransmitted,
          false, "PARSE_FAILED", entries);
    }
    List<ParsedEntry> parsed;
    try {
      parsed = parser.parse(body, manifest);
    } catch (ParseException e) {
      eventLedger.append(EventType.PARSE_FAILED, EventKeys.container(containerId),
          Map.of("reason", e.reasonCode(), "detail", String.valueOf(e.getMessage())));
      return new IngestResult(containerId, batchId, specVersion, digest, true, retransmitted,
          false, "PARSE_FAILED", entries);
    }

    // 4) 业务校验 + 身份关联（修订状态机）
    for (ParsedEntry entry : parsed) {
      EventKeys keys = EventKeys.revision(containerId, entry.sampleKey(), entry.reportNo(), entry.revisionNo());
      List<Issue> issues = validator.validate(entry, spec);
      if (!issues.isEmpty()) {
        Map<String, Object> payload = entryPayload(entry, specVersion, containerId);
        payload.put("reason", issues.get(0).code());
        payload.put("issues", issues.stream().map(i -> Map.of("code", i.code(), "message", i.message())).toList());
        var event = eventLedger.append(EventType.BUSINESS_REJECTED, keys, payload);
        entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
            "BUSINESS_REJECTED", issues.get(0).code(), event.getEventSeq()));
        continue;
      }

      ReportChainState chain = projector.loadState(entry.reportNo());
      Decision decision = correlator.decide(entry, chain);
      switch (decision.outcome()) {
        case ACCEPT -> {
          if (chain.head != null) {
            ReportChainState.Rev oldHead = chain.revisions.get(chain.head);
            Map<String, Object> supersededPayload = new LinkedHashMap<>();
            supersededPayload.put("supersededBy", entry.revisionNo());
            supersededPayload.put("digest", oldHead == null ? null : oldHead.digest);
            supersededPayload.put("specVersion", oldHead == null ? null : oldHead.specVersion);
            supersededPayload.put("sampleKey", oldHead == null ? null : oldHead.sampleKey);
            supersededPayload.put("containerId", containerId);
            eventLedger.append(EventType.REPORT_SUPERSEDED,
                EventKeys.revision(containerId, entry.sampleKey(), entry.reportNo(), chain.head),
                supersededPayload);
          }
          var event = eventLedger.append(EventType.REPORT_ACCEPTED, keys,
              entryPayload(entry, specVersion, containerId));
          entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
              "ACCEPTED", null, event.getEventSeq()));
        }
        case DUPLICATE_ARRIVAL -> {
          var event = eventLedger.append(EventType.DUPLICATE_ARRIVAL, keys,
              entryPayload(entry, specVersion, containerId));
          entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
              "DUPLICATE_ARRIVAL", "同报告修订且相同摘要，不重复应用", event.getEventSeq()));
        }
        case REVISION_CONFLICT -> {
          Map<String, Object> payload = entryPayload(entry, specVersion, containerId);
          payload.put("reason", decision.reason());
          var event = eventLedger.append(EventType.REVISION_CONFLICT, keys, payload);
          entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
              "REVISION_CONFLICT", decision.reason(), event.getEventSeq()));
        }
        case QUARANTINE -> {
          Map<String, Object> payload = entryPayload(entry, specVersion, containerId);
          payload.put("reason", decision.reason());
          var event = eventLedger.append(EventType.QUARANTINED, keys, payload);
          entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
              "QUARANTINED", decision.reason(), event.getEventSeq()));
        }
        case LATE_REVISION -> {
          Map<String, Object> payload = entryPayload(entry, specVersion, containerId);
          payload.put("reason", decision.reason());
          var event = eventLedger.append(EventType.LATE_REVISION_IGNORED, keys, payload);
          entries.add(new EntryResult(entry.sampleKey(), entry.reportNo(), entry.revisionNo(),
              "LATE_REVISION_IGNORED", decision.reason(), event.getEventSeq()));
        }
      }
    }
    return new IngestResult(containerId, batchId, specVersion, digest, true, retransmitted,
        false, "RECEIVED", entries);
  }

  private Map<String, Object> entryPayload(ParsedEntry entry, String specVersion, String containerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("containerId", containerId);
    payload.put("specVersion", specVersion);
    payload.put("sampleKey", entry.sampleKey());
    payload.put("reportNo", entry.reportNo());
    payload.put("revisionNo", entry.revisionNo());
    payload.put("supersedes", entry.supersedes());
    payload.put("analyte", entry.analyte());
    payload.put("resultValue", entry.resultValue().toPlainString());
    payload.put("resultUnit", entry.resultUnit());
    payload.put("detectionLimit",
        entry.detectionLimit() == null ? null : entry.detectionLimit().toPlainString());
    payload.put("detectionLimitUnit", entry.detectionLimitUnit());
    payload.put("conclusion", entry.conclusion());
    payload.put("attachmentRefs", entry.attachmentRefs());
    payload.put("digest", entry.digest());
    return payload;
  }

  private List<AttachmentDecl> parseManifest(String attachmentsJson) {
    if (attachmentsJson == null || attachmentsJson.isBlank()) {
      return List.of();
    }
    try {
      List<AttachmentDecl> manifest = new ArrayList<>();
      for (JsonNode node : objectMapper.readTree(attachmentsJson)) {
        manifest.add(new AttachmentDecl(
            node.path("id").asText(), node.path("sha256").asText()));
      }
      return manifest;
    } catch (Exception e) {
      throw ApiException.badRequest("BAD_ATTACHMENTS", "附件清单不是合法 JSON 数组: " + e.getMessage());
    }
  }

  private String toJson(List<AttachmentDecl> manifest) {
    try {
      return objectMapper.writeValueAsString(manifest);
    } catch (Exception e) {
      return "[]";
    }
  }
}
