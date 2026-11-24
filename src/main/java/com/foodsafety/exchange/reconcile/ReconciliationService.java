package com.foodsafety.exchange.reconcile;

import com.foodsafety.exchange.delivery.OperationLedger;
import com.foodsafety.exchange.delivery.OperationLedgerRepository;
import com.foodsafety.exchange.delivery.ReceiptRepository;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.BusinessEvent;
import com.foodsafety.exchange.event.BusinessEventRepository;
import com.foodsafety.exchange.inbound.InboundEnvelope;
import com.foodsafety.exchange.inbound.InboundEnvelopeRepository;
import com.foodsafety.exchange.projection.ReportHeadProjectionRepository;
import com.foodsafety.exchange.projection.ProjectionController;
import com.foodsafety.exchange.projection.ReportRevisionProjection;
import com.foodsafety.exchange.projection.ReportRevisionProjectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 对账服务：按容器、样品、报告修订、操作号与回执组装证据链。
 * 证据 = 不可变入站信封 + 事件账 + 当前投影 + 操作账 + 回执。
 */
@Service
public class ReconciliationService {

  private final InboundEnvelopeRepository envelopeRepo;
  private final BusinessEventRepository eventRepo;
  private final ReportRevisionProjectionRepository revisionRepo;
  private final ReportHeadProjectionRepository headRepo;
  private final OperationLedgerRepository ledgerRepo;
  private final ReceiptRepository receiptRepo;

  public ReconciliationService(InboundEnvelopeRepository envelopeRepo,
      BusinessEventRepository eventRepo, ReportRevisionProjectionRepository revisionRepo,
      ReportHeadProjectionRepository headRepo, OperationLedgerRepository ledgerRepo,
      ReceiptRepository receiptRepo) {
    this.envelopeRepo = envelopeRepo;
    this.eventRepo = eventRepo;
    this.revisionRepo = revisionRepo;
    this.headRepo = headRepo;
    this.ledgerRepo = ledgerRepo;
    this.receiptRepo = receiptRepo;
  }

  /** 容器证据链：信封 -> 条目（样品/报告/修订） -> 操作 -> 回执，附事件序列。 */
  public Map<String, Object> containerEvidence(String containerId) {
    InboundEnvelope envelope = envelopeRepo.findFirstByContainerIdOrderById(containerId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_CONTAINER", "容器不存在: " + containerId));
    List<BusinessEvent> events = eventRepo.findByContainerIdOrderByEventSeq(containerId);

    // 容器内的报告修订条目及其当前状态
    Set<String> seen = new LinkedHashSet<>();
    List<Map<String, Object>> entries = new ArrayList<>();
    List<Map<String, Object>> operations = new ArrayList<>();
    Set<String> seenOperations = new LinkedHashSet<>();
    for (BusinessEvent event : events) {
      if (event.getReportNo() == null || event.getRevisionNo() == null) {
        continue;
      }
      String key = event.getReportNo() + "#" + event.getRevisionNo();
      if (seen.add(key)) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sampleKey", event.getSampleKey());
        entry.put("reportNo", event.getReportNo());
        entry.put("revisionNo", event.getRevisionNo());
        revisionRepo.findByReportNoAndRevisionNo(event.getReportNo(), event.getRevisionNo())
            .ifPresentOrElse(row -> {
              entry.put("currentStatus", row.getStatus());
              entry.put("digest", row.getDigest());
            }, () -> entry.put("currentStatus", null));
        entries.add(entry);
        for (OperationLedger op : ledgerRepo.findByReportNoAndRevisionNo(
            event.getReportNo(), event.getRevisionNo())) {
          if (seenOperations.add(op.getOperationNo())) {
            operations.add(operationView(op));
          }
        }
      }
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("container", envelopeView(envelope));
    view.put("entries", entries);
    view.put("operations", operations);
    view.put("events", events.stream().map(ReconciliationService::eventView).toList());
    return view;
  }

  /** 报告证据链：链头 -> 各修订（含规范版本/摘要/容器） -> 操作 -> 回执。 */
  public Map<String, Object> reportEvidence(String reportNo) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reportNo", reportNo);
    headRepo.findById(reportNo).ifPresent(head -> {
      view.put("sampleKey", head.getSampleKey());
      view.put("head", head.getHeadRevision());
      view.put("headStatus", head.getHeadStatus());
      view.put("partitionVersion", head.getPartitionVersion());
    });
    view.put("revisions", revisionRepo.findByReportNoOrderByRevisionNo(reportNo).stream()
        .map(ProjectionController::revisionView).toList());
    view.put("operations", ledgerRepo.findByReportNoOrderByOperationNo(reportNo).stream()
        .map(this::operationView).toList());
    view.put("events", eventRepo.findByReportNoOrderByEventSeq(reportNo).stream()
        .map(ReconciliationService::eventView).toList());
    return view;
  }

  /** 样品证据链：样品键 -> 报告链头 -> 修订。不按相似内容合并样品。 */
  public Map<String, Object> sampleEvidence(String sampleKey) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sampleKey", sampleKey);
    view.put("heads", headRepo.findBySampleKeyOrderByReportNo(sampleKey).stream()
        .map(head -> {
          Map<String, Object> h = new LinkedHashMap<String, Object>();
          h.put("reportNo", head.getReportNo());
          h.put("head", head.getHeadRevision());
          h.put("headStatus", head.getHeadStatus());
          h.put("partitionVersion", head.getPartitionVersion());
          return h;
        }).toList());
    view.put("revisions", revisionRepo.findBySampleKeyOrderByReportNo(sampleKey).stream()
        .map(ProjectionController::revisionView).toList());
    return view;
  }

  /** 操作号证据链：操作 -> 回执 -> 报告修订 -> 容器，附事件序列。 */
  public Map<String, Object> operationEvidence(String operationNo) {
    OperationLedger operation = ledgerRepo.findById(operationNo)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_OPERATION", "操作号不存在: " + operationNo));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("operation", operationView(operation));
    revisionRepo.findByReportNoAndRevisionNo(operation.getReportNo(), operation.getRevisionNo())
        .ifPresent(row -> {
          view.put("reportRevision", ProjectionController.revisionView(row));
          if (row.getContainerId() != null) {
            envelopeRepo.findFirstByContainerIdOrderById(row.getContainerId())
                .ifPresent(envelope -> view.put("container", envelopeView(envelope)));
          }
        });
    view.put("events", eventRepo.findByOperationNoOrderByEventSeq(operationNo).stream()
        .map(ReconciliationService::eventView).toList());
    return view;
  }

  private Map<String, Object> operationView(OperationLedger operation) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("operationNo", operation.getOperationNo());
    view.put("status", operation.getStatus());
    view.put("reportNo", operation.getReportNo());
    view.put("revisionNo", operation.getRevisionNo());
    view.put("deliveryType", operation.getDeliveryType());
    view.put("payloadSha256", operation.getPayloadSha256());
    view.put("duplicateOf", operation.getDuplicateOf());
    view.put("receipts", receiptRepo.findByOperationNoOrderByIssuedAt(operation.getOperationNo())
        .stream().map(r -> {
          Map<String, Object> receipt = new LinkedHashMap<String, Object>();
          receipt.put("receiptId", r.getReceiptId());
          receipt.put("receiptType", r.getReceiptType());
          receipt.put("issuer", r.getIssuer());
          receipt.put("issuedAt", r.getIssuedAt().toString());
          return receipt;
        }).toList());
    return view;
  }

  private Map<String, Object> envelopeView(InboundEnvelope envelope) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("containerId", envelope.getContainerId());
    view.put("batchId", envelope.getBatchId());
    view.put("sender", envelope.getSender());
    view.put("specVersion", envelope.getSpecVersion());
    view.put("payloadSha256", envelope.getPayloadSha256());
    view.put("signatureValid", envelope.isSignatureValid());
    view.put("receivedAt", envelope.getReceivedAt().toString());
    view.put("immutable", true);
    return view;
  }

  static Map<String, Object> eventView(BusinessEvent event) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("eventSeq", event.getEventSeq());
    view.put("eventType", event.getEventType());
    view.put("reportNo", event.getReportNo());
    view.put("revisionNo", event.getRevisionNo());
    view.put("operationNo", event.getOperationNo());
    view.put("occurredAt", event.getOccurredAt().toString());
    view.put("payload", event.getPayload());
    return view;
  }
}
