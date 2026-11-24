package com.foodsafety.exchange.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.domain.OperationStatus;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.EventKeys;
import com.foodsafety.exchange.event.EventLedger;
import com.foodsafety.exchange.event.EventType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回执协调器：操作处于“超时未知”时，先按原操作号查询目标状态，再决定是否重试；
 * 绝不因重试而新建操作号。
 */
@Service
public class ReceiptCoordinator {

  private final OperationLedgerRepository ledgerRepo;
  private final ReceiptRepository receiptRepo;
  private final EventLedger eventLedger;
  private final TargetGateway targetGateway;
  private final DeliveryService deliveryService;
  private final ObjectMapper objectMapper;

  public ReceiptCoordinator(OperationLedgerRepository ledgerRepo, ReceiptRepository receiptRepo,
      EventLedger eventLedger, TargetGateway targetGateway, DeliveryService deliveryService,
      ObjectMapper objectMapper) {
    this.ledgerRepo = ledgerRepo;
    this.receiptRepo = receiptRepo;
    this.eventLedger = eventLedger;
    this.targetGateway = targetGateway;
    this.deliveryService = deliveryService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public DeliveryService.DeliveryResult resolveUnknown(String operationNo) {
    OperationLedger operation = ledgerRepo.findById(operationNo)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_OPERATION", "操作号不存在: " + operationNo));

    switch (OperationStatus.valueOf(operation.getStatus())) {
      case ACCEPTED -> {
        Receipt receipt = deliveryService.latestReceipt(operationNo);
        return new DeliveryService.DeliveryResult(operationNo, operation.getStatus(),
            receipt == null ? null : receipt.getReceiptId(),
            receipt == null ? null : receipt.getReceiptType(),
            null, true, operation.getLastEventSeq(), "操作已受理，无需重试");
      }
      case CONFLICT -> {
        return new DeliveryService.DeliveryResult(operationNo, operation.getStatus(),
            null, null, null, false, operation.getLastEventSeq(), "操作存在冲突，须人工处理");
      }
      case DUPLICATE -> {
        return new DeliveryService.DeliveryResult(operationNo, operation.getStatus(),
            null, null, operation.getDuplicateOf(), false, operation.getLastEventSeq(),
            "重复到达的操作，未重复应用");
      }
      default -> {
        // UNKNOWN / PENDING / FAILED：进入协调流程
      }
    }

    // 第一步：按原操作号查询目标状态
    Optional<TargetGateway.TargetStatusView> target;
    try {
      target = targetGateway.query(operationNo);
    } catch (TargetUnavailableException e) {
      var event = eventLedger.append(EventType.DELIVERY_UNKNOWN,
          EventKeys.operation(operationNo, operation.getReportNo(), operation.getRevisionNo()),
          Map.of("during", "TARGET_QUERY", "error", String.valueOf(e.getMessage())));
      operation.updateStatus(OperationStatus.UNKNOWN.name(), event.getEventSeq());
      ledgerRepo.save(operation);
      return new DeliveryService.DeliveryResult(operationNo, OperationStatus.UNKNOWN.name(),
          null, null, null, false, event.getEventSeq(), "目标状态查询超时，保持未知");
    }

    if (target.isPresent() && "ACCEPTED".equals(target.get().status())) {
      // 目标已受理：登记回执，本地收敛为已受理
      String receiptId = null;
      String receiptType = null;
      for (TargetGateway.TargetReceiptView view : target.get().receipts()) {
        if (receiptRepo.findById(view.receiptId()).isEmpty()) {
          var receiptEvent = eventLedger.append(EventType.RECEIPT_RECORDED,
              EventKeys.operation(operationNo, operation.getReportNo(), operation.getRevisionNo()),
              Map.of("receiptId", view.receiptId(), "receiptType", view.receiptType(),
                  "issuer", "target", "issuedAt", view.issuedAt()));
          receiptRepo.save(new Receipt(view.receiptId(), operationNo, view.receiptType(),
              "target", null, OffsetDateTime.parse(view.issuedAt()), receiptEvent.getEventSeq()));
        }
        receiptId = view.receiptId();
        receiptType = view.receiptType();
      }
      var event = eventLedger.append(EventType.DELIVERY_CONFIRMED,
          EventKeys.operation(operationNo, operation.getReportNo(), operation.getRevisionNo()),
          Map.of("via", "TARGET_QUERY"));
      operation.updateStatus(OperationStatus.ACCEPTED.name(), event.getEventSeq());
      ledgerRepo.save(operation);
      return new DeliveryService.DeliveryResult(operationNo, OperationStatus.ACCEPTED.name(),
          receiptId, receiptType, null, false, event.getEventSeq(),
          "目标已受理，本地经原操作号查询收敛为已受理");
    }

    if (target.isEmpty()) {
      // 目标未收到：以原操作号与原载荷重试（不新建操作）
      try {
        JsonNode payload = objectMapper.readTree(operation.getRequestPayload());
        var request = new DeliveryService.DeliveryRequest(operation.getOperationNo(),
            operation.getReportNo(), operation.getRevisionNo(), operation.getDeliveryType(), payload);
        return deliveryService.attemptDelivery(operation, request, operation.getPayloadSha256());
      } catch (Exception e) {
        throw new IllegalStateException("操作账载荷无法解析", e);
      }
    }

    // 目标存在但状态非已受理：保持未知
    var event = eventLedger.append(EventType.DELIVERY_UNKNOWN,
        EventKeys.operation(operationNo, operation.getReportNo(), operation.getRevisionNo()),
        Map.of("during", "TARGET_QUERY", "targetStatus", target.get().status()));
    operation.updateStatus(OperationStatus.UNKNOWN.name(), event.getEventSeq());
    ledgerRepo.save(operation);
    return new DeliveryService.DeliveryResult(operationNo, OperationStatus.UNKNOWN.name(),
        null, null, null, false, event.getEventSeq(), "目标状态非已受理，保持未知");
  }
}
