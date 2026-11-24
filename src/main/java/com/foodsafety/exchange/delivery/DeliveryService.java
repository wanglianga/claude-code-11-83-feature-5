package com.foodsafety.exchange.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.foodsafety.exchange.domain.OperationStatus;
import com.foodsafety.exchange.domain.ReceiptType;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.EventKeys;
import com.foodsafety.exchange.event.EventLedger;
import com.foodsafety.exchange.event.EventType;
import com.foodsafety.exchange.projection.ReportRevisionProjection;
import com.foodsafety.exchange.projection.ReportRevisionProjectionRepository;
import com.foodsafety.exchange.util.Digests;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递服务：以操作号为幂等键的操作账。
 * 相同操作号+相同载荷 -> 返回原操作结果；相同操作号+不同载荷 -> 冲突；
 * 不同操作号+同报告修订+相同摘要 -> 记录重复到达而不重复应用。
 */
@Service
public class DeliveryService {

  private static final Set<String> DELIVERY_TYPES = Set.of("REPORT", "DISPOSAL");
  private static final List<String> APPLIED_STATUSES =
      List.of(OperationStatus.ACCEPTED.name(), OperationStatus.UNKNOWN.name(), OperationStatus.PENDING.name());

  private final OperationLedgerRepository ledgerRepo;
  private final ReceiptRepository receiptRepo;
  private final ReportRevisionProjectionRepository revisionRepo;
  private final EventLedger eventLedger;
  private final TargetGateway targetGateway;

  public DeliveryService(OperationLedgerRepository ledgerRepo, ReceiptRepository receiptRepo,
      ReportRevisionProjectionRepository revisionRepo, EventLedger eventLedger,
      TargetGateway targetGateway) {
    this.ledgerRepo = ledgerRepo;
    this.receiptRepo = receiptRepo;
    this.revisionRepo = revisionRepo;
    this.eventLedger = eventLedger;
    this.targetGateway = targetGateway;
  }

  public record DeliveryRequest(String operationNo, String reportNo, Integer revisionNo,
      String deliveryType, JsonNode payload) {}

  public record DeliveryResult(String operationNo, String status, String receiptId,
      String receiptType, String duplicateOf, boolean replayed, Long eventSeq, String detail) {}

  @Transactional
  public DeliveryResult deliver(DeliveryRequest request) {
    validate(request);
    String canonical = Digests.canonicalJson(request.payload());
    String hash = Digests.sha256Hex(canonical);

    OperationLedger existing = ledgerRepo.findById(request.operationNo()).orElse(null);
    if (existing != null) {
      if (existing.getPayloadSha256().equals(hash)) {
        // 相同操作号 + 相同载荷：只返回原操作结果
        Receipt receipt = latestReceipt(request.operationNo());
        return new DeliveryResult(existing.getOperationNo(), existing.getStatus(),
            receipt == null ? null : receipt.getReceiptId(),
            receipt == null ? null : receipt.getReceiptType(),
            existing.getDuplicateOf(), true, existing.getLastEventSeq(), "相同操作号与相同载荷，返回原操作结果");
      }
      // 相同操作号 + 不同载荷：冲突
      var event = eventLedger.append(EventType.DELIVERY_CONFLICT,
          EventKeys.operation(request.operationNo(), request.reportNo(), request.revisionNo()),
          Map.of("source", "LOCAL", "existingPayloadSha256", existing.getPayloadSha256(),
              "incomingPayloadSha256", hash));
      return new DeliveryResult(existing.getOperationNo(), OperationStatus.CONFLICT.name(),
          null, null, null, false, event.getEventSeq(), "相同操作号提交了不同载荷");
    }

    // 引用的报告修订必须存在且曾被受理
    ReportRevisionProjection revision = revisionRepo
        .findByReportNoAndRevisionNo(request.reportNo(), request.revisionNo())
        .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_REVISION",
            "报告修订不存在: " + request.reportNo() + " 修订 " + request.revisionNo()));
    String revisionStatus = revision.getStatus();
    if (!revisionStatus.equals("ACCEPTED") && !revisionStatus.equals("SUPERSEDED")) {
      throw ApiException.unprocessable("REVISION_NOT_DELIVERABLE",
          "报告修订状态为 " + revisionStatus + "，不可投递");
    }

    // 不同操作号 + 同报告修订 + 相同摘要：记录重复到达，不重复应用
    var duplicate = ledgerRepo.findFirstByReportNoAndRevisionNoAndPayloadSha256AndStatusIn(
        request.reportNo(), request.revisionNo(), hash, APPLIED_STATUSES);
    if (duplicate.isPresent()) {
      OperationLedger original = duplicate.get();
      var event = eventLedger.append(EventType.DUPLICATE_ARRIVAL,
          EventKeys.operation(request.operationNo(), request.reportNo(), request.revisionNo()),
          Map.of("duplicateOf", original.getOperationNo(), "digest", hash));
      OperationLedger row = new OperationLedger(request.operationNo(), hash,
          targetGateway.targetEndpoint(), request.deliveryType(), request.reportNo(),
          request.revisionNo(), canonical, OperationStatus.DUPLICATE.name(), event.getEventSeq());
      row.markDuplicate(original.getOperationNo(), event.getEventSeq());
      ledgerRepo.save(row);
      Receipt receipt = latestReceipt(original.getOperationNo());
      return new DeliveryResult(request.operationNo(), OperationStatus.DUPLICATE.name(),
          receipt == null ? null : receipt.getReceiptId(),
          receipt == null ? null : receipt.getReceiptType(),
          original.getOperationNo(), false, event.getEventSeq(),
          "同报告修订且相同摘要的重复到达，不重复应用");
    }

    // 新操作：登记操作账并尝试投递
    var requested = eventLedger.append(EventType.DELIVERY_REQUESTED,
        EventKeys.operation(request.operationNo(), request.reportNo(), request.revisionNo()),
        Map.of("payloadSha256", hash, "reportNo", request.reportNo(),
            "revisionNo", request.revisionNo(), "deliveryType", request.deliveryType(),
            "target", targetGateway.targetEndpoint()));
    OperationLedger operation = new OperationLedger(request.operationNo(), hash,
        targetGateway.targetEndpoint(), request.deliveryType(), request.reportNo(),
        request.revisionNo(), canonical, OperationStatus.PENDING.name(), requested.getEventSeq());
    ledgerRepo.save(operation);

    return attemptDelivery(operation, request, hash);
  }

  /** 执行一次目标端写入尝试并登记结果（供投递与协调器重试共用）。 */
  DeliveryResult attemptDelivery(OperationLedger operation, DeliveryRequest request, String hash) {
    try {
      var response = targetGateway.deliver(new TargetGateway.TargetDeliveryRequest(
          operation.getOperationNo(), operation.getReportNo(), operation.getRevisionNo(),
          operation.getDeliveryType(), request.payload(), hash));
      return onAccepted(operation, response.receipt(), EventType.DELIVERY_ACCEPTED, "目标端已受理");
    } catch (TargetConflictException e) {
      var event = eventLedger.append(EventType.DELIVERY_CONFLICT,
          EventKeys.operation(operation.getOperationNo(), operation.getReportNo(), operation.getRevisionNo()),
          Map.of("source", "TARGET", "detail", String.valueOf(e.getMessage())));
      operation.updateStatus(OperationStatus.CONFLICT.name(), event.getEventSeq());
      ledgerRepo.save(operation);
      return new DeliveryResult(operation.getOperationNo(), OperationStatus.CONFLICT.name(),
          null, null, null, false, event.getEventSeq(), e.getMessage());
    } catch (TargetUnavailableException e) {
      var event = eventLedger.append(EventType.DELIVERY_UNKNOWN,
          EventKeys.operation(operation.getOperationNo(), operation.getReportNo(), operation.getRevisionNo()),
          Map.of("error", String.valueOf(e.getMessage())));
      operation.updateStatus(OperationStatus.UNKNOWN.name(), event.getEventSeq());
      ledgerRepo.save(operation);
      return new DeliveryResult(operation.getOperationNo(), OperationStatus.UNKNOWN.name(),
          null, null, null, false, event.getEventSeq(), "投递超时，结果未知");
    }
  }

  /** 目标端确认受理：登记回执与事件。 */
  DeliveryResult onAccepted(OperationLedger operation, TargetGateway.TargetReceiptView receiptView,
      EventType confirmEvent, String detail) {
    String receiptId = null;
    String receiptType = null;
    if (receiptView != null && receiptRepo.findById(receiptView.receiptId()).isEmpty()) {
      var receiptEvent = eventLedger.append(EventType.RECEIPT_RECORDED,
          EventKeys.operation(operation.getOperationNo(), operation.getReportNo(), operation.getRevisionNo()),
          Map.of("receiptId", receiptView.receiptId(), "receiptType", receiptView.receiptType(),
              "issuer", "target", "issuedAt", receiptView.issuedAt()));
      receiptRepo.save(new Receipt(receiptView.receiptId(), operation.getOperationNo(),
          receiptView.receiptType(), "target", null,
          OffsetDateTime.parse(receiptView.issuedAt()), receiptEvent.getEventSeq()));
    }
    if (receiptView != null) {
      receiptId = receiptView.receiptId();
      receiptType = receiptView.receiptType();
    }
    var event = eventLedger.append(confirmEvent,
        EventKeys.operation(operation.getOperationNo(), operation.getReportNo(), operation.getRevisionNo()),
        Map.of("receiptId", receiptId == null ? "" : receiptId));
    operation.updateStatus(OperationStatus.ACCEPTED.name(), event.getEventSeq());
    ledgerRepo.save(operation);
    return new DeliveryResult(operation.getOperationNo(), OperationStatus.ACCEPTED.name(),
        receiptId, receiptType, null, false, event.getEventSeq(), detail);
  }

  Receipt latestReceipt(String operationNo) {
    List<Receipt> receipts = receiptRepo.findByOperationNoOrderByIssuedAt(operationNo);
    return receipts.isEmpty() ? null : receipts.get(receipts.size() - 1);
  }

  private void validate(DeliveryRequest request) {
    if (request.operationNo() == null || request.operationNo().isBlank()) {
      throw ApiException.badRequest("MISSING_OPERATION_NO", "缺少操作号");
    }
    if (request.reportNo() == null || request.reportNo().isBlank()
        || request.revisionNo() == null) {
      throw ApiException.badRequest("MISSING_REPORT_REF", "缺少报告号或修订号");
    }
    if (request.deliveryType() == null || !DELIVERY_TYPES.contains(request.deliveryType())) {
      throw ApiException.badRequest("INVALID_DELIVERY_TYPE",
          "投递类型须为 " + DELIVERY_TYPES + " 之一");
    }
    if (request.payload() == null || request.payload().isNull()) {
      throw ApiException.badRequest("MISSING_PAYLOAD", "缺少投递载荷");
    }
  }
}
