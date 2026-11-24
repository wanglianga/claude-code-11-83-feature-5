package com.foodsafety.exchange.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.trace.TraceSnapshot.FactoryRec;
import com.foodsafety.exchange.trace.TraceSnapshot.LineRec;
import com.foodsafety.exchange.trace.TraceSnapshot.ProductBatchRec;
import com.foodsafety.exchange.trace.TraceSnapshot.RawBatchRec;
import com.foodsafety.exchange.trace.TraceSnapshot.SampleRec;
import com.foodsafety.exchange.trace.TraceSnapshot.SpectrumRec;
import com.foodsafety.exchange.trace.TraceSnapshot.UsageRec;
import com.foodsafety.exchange.util.Digests;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 追溯服务：从数据库装载生产图与污染谱 -> 路径求值 -> 输出候选；
 * 可把求值输入与结果冻结为不可变追溯版本，供风险沟通与复现。
 */
@Service
public class TraceService {

  private final TraceFactoryRepository factoryRepo;
  private final TraceLineRepository lineRepo;
  private final TraceProductBatchRepository productBatchRepo;
  private final TraceRawBatchRepository rawBatchRepo;
  private final TraceUsageRepository usageRepo;
  private final TraceSampleRepository sampleRepo;
  private final TraceSpectrumRepository spectrumRepo;
  private final TraceVersionRepository versionRepo;
  private final TraceEvaluator evaluator;
  private final TraceCodec codec;
  private final ObjectMapper mapper;

  public TraceService(TraceFactoryRepository factoryRepo, TraceLineRepository lineRepo,
      TraceProductBatchRepository productBatchRepo, TraceRawBatchRepository rawBatchRepo,
      TraceUsageRepository usageRepo, TraceSampleRepository sampleRepo,
      TraceSpectrumRepository spectrumRepo, TraceVersionRepository versionRepo,
      TraceEvaluator evaluator, TraceCodec codec, ObjectMapper mapper) {
    this.factoryRepo = factoryRepo;
    this.lineRepo = lineRepo;
    this.productBatchRepo = productBatchRepo;
    this.rawBatchRepo = rawBatchRepo;
    this.usageRepo = usageRepo;
    this.sampleRepo = sampleRepo;
    this.spectrumRepo = spectrumRepo;
    this.versionRepo = versionRepo;
    this.evaluator = evaluator;
    this.codec = codec;
    this.mapper = mapper;
  }

  private record Loaded(TraceSnapshot graph, TraceSnapshot withSpectra) {}

  private Loaded loadAll() {
    List<FactoryRec> factories = factoryRepo.findAllByOrderByFactoryId().stream()
        .map(f -> new FactoryRec(f.getFactoryId(), f.getName(), f.getRegion())).toList();
    List<LineRec> lines = lineRepo.findAllByOrderByLineId().stream()
        .map(l -> new LineRec(l.getLineId(), l.getFactoryId(), l.getName())).toList();
    List<ProductBatchRec> productBatches = productBatchRepo.findAllByOrderByProductBatchId()
        .stream().map(p -> new ProductBatchRec(p.getProductBatchId(), p.getLineId(),
            p.getProductCode(), p.getProductionStart(), p.getProductionEnd())).toList();
    List<RawBatchRec> rawBatches = rawBatchRepo.findAllByOrderByRawBatchId().stream()
        .map(r -> new RawBatchRec(r.getRawBatchId(), r.getIngredientCode(), r.getSupplierId(),
            r.getPurchaseFrom(), r.getPurchaseTo(), r.getArrivalFrom(), r.getArrivalTo(),
            parseFingerprint(r.getFingerprint()))).toList();
    List<UsageRec> usages = usageRepo.findAllByOrderByUsageId().stream()
        .map(u -> new UsageRec(u.getProductBatchId(), u.getRawBatchId(), u.getIngredientCode(),
            u.getUsedFrom(), u.getUsedTo(), u.getQuantityKg())).toList();
    List<SampleRec> samples = sampleRepo.findAllByOrderBySampleKey().stream()
        .map(s -> new SampleRec(s.getSampleKey(), s.getProductBatchId(), s.getSampledAt(),
            s.getNote())).toList();
    List<SpectrumRec> spectra = spectrumRepo.findAllByOrderBySampleKeyAscMeasuredAtAsc().stream()
        .map(s -> new SpectrumRec(s.getSpectrumId(), s.getSampleKey(), s.getAnalyte(),
            parseFingerprint(s.getFingerprint()), s.getDetectionLimit(), s.getDetectionLimitUnit(),
            s.getRelativeUncertainty(), s.getResultValue(), s.getPolarity(), s.getLab(),
            s.getMeasuredAt())).toList();
    TraceSnapshot graph = new TraceSnapshot(factories, lines, productBatches, rawBatches,
        usages, samples, List.of());
    TraceSnapshot full = graph.withSpectra(spectra);
    return new Loaded(graph, full);
  }

  /** 求值（不冻结版本）。 */
  public TraceResult evaluate(TraceEvalParams params) {
    Loaded loaded = loadAll();
    return evaluator.evaluate(loaded.withSpectra, params);
  }

  /**
   * 求值并冻结追溯版本：固定本次使用的生产图、实验结果与候选输出。
   * 返回冻结版本视图（含输出与摘要）。
   */
  @Transactional
  public Map<String, Object> freeze(String caseId, String purpose, TraceEvalParams params) {
    if (caseId == null || caseId.isBlank()) {
      throw ApiException.badRequest("CASE_ID_REQUIRED", "caseId 不能为空");
    }
    Loaded loaded = loadAll();
    // 按 asOf 与样品范围截断实验结果后求值，保证版本冻结的实验结果与求值输入一致
    List<SpectrumRec> evaluatedSpectra = loaded.withSpectra.spectra().stream()
        .filter(s -> params.asOf() == null || !s.measuredAt().isAfter(params.asOf()))
        .filter(s -> params.onlySampleKeys() == null
            || params.onlySampleKeys().contains(s.sampleKey()))
        .toList();
    TraceSnapshot graphSnapshot = loaded.graph.withSpectra(List.of());
    TraceSnapshot evalSnapshot = graphSnapshot.withSpectra(evaluatedSpectra);
    TraceResult result = evaluator.evaluate(evalSnapshot, params);

    JsonNode graphJson = codec.graph(graphSnapshot);
    JsonNode spectraJson = codec.spectra(evalSnapshot);
    String graphSha = Digests.sha256Hex(canonical(graphJson));
    String spectraSha = Digests.sha256Hex(canonical(spectraJson));

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("graph", graphJson);
    record.put("spectra", spectraJson);
    record.put("result", result);
    Map<String, Object> replayParams = new LinkedHashMap<>();
    replayParams.put("analyte", params.analyte());
    replayParams.put("mode", params.mode());
    replayParams.put("tolerance", params.tolerance());
    replayParams.put("useFingerprint", params.useFingerprint());
    replayParams.put("backgroundRate", params.backgroundRate());
    replayParams.put("sampleKeys", params.onlySampleKeys() == null ? List.of() : params.onlySampleKeys());
    replayParams.put("asOf", params.asOf() == null ? null : params.asOf().toString());
    record.put("params", replayParams);
    JsonNode recordJson = codec.node(record);
    String inputsSha = Digests.sha256Hex(canonical(recordJson));

    String versionId = "TV-" + Digests.randomId().substring(0, 12);
    String payload;
    try {
      payload = mapper.writeValueAsString(recordJson);
    } catch (Exception e) {
      throw new IllegalStateException("追溯版本序列化失败", e);
    }
    TraceVersion version = new TraceVersion(versionId, caseId,
        purpose == null || purpose.isBlank() ? "RISK_COMMUNICATION" : purpose,
        params.mode(), params.analyte(), params.asOf(), graphSha, spectraSha, inputsSha,
        payload, OffsetDateTime.now());
    versionRepo.save(version);
    return versionView(version, result);
  }

  public Map<String, Object> getVersion(String caseId, String versionId) {
    TraceVersion version = versionRepo.findByCaseIdAndVersionId(caseId, versionId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_TRACE_VERSION",
            "追溯版本不存在: " + caseId + "/" + versionId));
    return versionView(version, null);
  }

  public List<Map<String, Object>> listVersions(String caseId) {
    List<TraceVersion> versions = caseId == null || caseId.isBlank()
        ? versionRepo.findAllByOrderByCreatedAtAsc()
        : versionRepo.findByCaseIdOrderByCreatedAtAsc(caseId);
    List<Map<String, Object>> list = new ArrayList<>();
    for (TraceVersion v : versions) {
      list.add(versionSummary(v));
    }
    return list;
  }

  /**
   * 复现校验：从冻结的生产图与实验结果重新求值，与冻结输出比较。
   * 证明候选推导可仅凭追溯版本复现，不依赖当前库内状态。
   */
  public Map<String, Object> verifyVersion(String caseId, String versionId) {
    TraceVersion version = versionRepo.findByCaseIdAndVersionId(caseId, versionId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_TRACE_VERSION",
            "追溯版本不存在: " + caseId + "/" + versionId));
    JsonNode payload = readTree(version.getPayload());
    TraceSnapshot snapshot = codec.snapshot(payload.path("graph"), payload.path("spectra"));
    JsonNode resultJson = payload.path("result");
    TraceEvalParams params = new TraceEvalParams(
        resultJson.path("analyte").asText(),
        resultJson.path("mode").asText(),
        resultJson.path("assumptions").path("tolerance").asDouble(),
        resultJson.path("assumptions").path("backgroundRateDefaulted").asBoolean()
            ? null : resultJson.path("assumptions").path("backgroundRateUsed").asDouble(),
        version.getAsOf() == null ? null : version.getAsOf(),
        null,
        resultJson.path("assumptions").path("fingerprintEvidenceUsed").asBoolean(true));
    TraceResult recomputed = evaluator.evaluate(snapshot, params);
    String recomputedJson = canonical(codec.node(recomputed));
    String frozenJson = canonical(resultJson);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("caseId", caseId);
    view.put("versionId", versionId);
    view.put("reproducible", recomputedJson.equals(frozenJson));
    view.put("recomputedSummary", summarize(recomputed));
    view.put("frozenSummary", summarize(mapper.convertValue(resultJson, TraceResult.class)));
    return view;
  }

  public Map<String, Object> graphSummary() {
    Loaded loaded = loadAll();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("factories", loaded.graph.factories().size());
    view.put("lines", loaded.graph.lines().size());
    view.put("productBatches", loaded.graph.productBatches().size());
    view.put("rawBatches", loaded.graph.rawBatches().size());
    view.put("usages", loaded.graph.usages().size());
    view.put("samples", loaded.graph.samples().size());
    view.put("spectra", loaded.withSpectra.spectra().size());
    view.put("analytes", loaded.withSpectra.spectra().stream().map(SpectrumRec::analyte)
        .distinct().sorted().toList());
    return view;
  }

  // ---------------------------------------------------------------- helpers

  private Map<String, Object> versionView(TraceVersion version, TraceResult result) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("versionId", version.getVersionId());
    view.put("caseId", version.getCaseId());
    view.put("purpose", version.getPurpose());
    view.put("mode", version.getMode());
    view.put("analyte", version.getAnalyte());
    view.put("asOf", version.getAsOf() == null ? null : version.getAsOf().toString());
    view.put("graphSha256", version.getGraphSha256());
    view.put("spectraSha256", version.getSpectraSha256());
    view.put("inputsSha256", version.getInputsSha256());
    view.put("createdAt", version.getCreatedAt().toString());
    view.put("immutable", true);
    view.put("legalLiability", false);
    if (result != null) {
      view.put("result", result);
    } else {
      JsonNode payload = readTree(version.getPayload());
      view.put("result", payload.path("result"));
      view.put("graph", payload.path("graph"));
      view.put("spectra", payload.path("spectra"));
    }
    return view;
  }

  private Map<String, Object> versionSummary(TraceVersion v) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("versionId", v.getVersionId());
    view.put("caseId", v.getCaseId());
    view.put("purpose", v.getPurpose());
    view.put("mode", v.getMode());
    view.put("analyte", v.getAnalyte());
    view.put("asOf", v.getAsOf() == null ? null : v.getAsOf().toString());
    view.put("inputsSha256", v.getInputsSha256());
    view.put("createdAt", v.getCreatedAt().toString());
    return view;
  }

  private Map<String, Object> summarize(TraceResult r) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("status", r.status());
    view.put("candidateCount", r.candidateCount());
    view.put("candidateRawBatches", r.candidates().stream()
        .map(TraceResult.Candidate::rawBatchId).toList());
    view.put("prunedRawBatches", r.pruned().stream().map(TraceResult.Pruned::rawBatchId).toList());
    view.put("unresolvedSamples", r.unresolved().stream()
        .map(TraceResult.Unresolved::sampleKey).toList());
    return view;
  }

  private String canonical(JsonNode node) {
    return Digests.canonicalJson(node);
  }

  private JsonNode readTree(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("追溯版本载荷解析失败", e);
    }
  }

  private Map<String, Double> parseFingerprint(String json) {
    Map<String, Double> map = new LinkedHashMap<>();
    if (json == null || json.isBlank()) {
      return map;
    }
    try {
      JsonNode node = mapper.readTree(json);
      node.fields().forEachRemaining(e -> {
        if (e.getValue().isNumber()) {
          map.put(e.getKey(), e.getValue().asDouble());
        }
      });
    } catch (Exception ignored) {
      // 指纹无法解析视为未知
    }
    return map;
  }

  static BigDecimal bd(String v) {
    return v == null || v.isBlank() ? null : new BigDecimal(v);
  }
}
