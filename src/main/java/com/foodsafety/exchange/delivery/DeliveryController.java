package com.foodsafety.exchange.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.foodsafety.exchange.domain.OperationStatus;
import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.BusinessEvent;
import com.foodsafety.exchange.event.BusinessEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 投递操作接口：幂等投递、状态查询、未知重试。 */
@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "投递操作", description = "以操作号为幂等键的目标端投递与未知协调")
public class DeliveryController {

  private final DeliveryService deliveryService;
  private final ReceiptCoordinator receiptCoordinator;
  private final OperationLedgerRepository ledgerRepo;
  private final ReceiptRepository receiptRepo;
  private final BusinessEventRepository eventRepo;

  public DeliveryController(DeliveryService deliveryService, ReceiptCoordinator receiptCoordinator,
      OperationLedgerRepository ledgerRepo, ReceiptRepository receiptRepo,
      BusinessEventRepository eventRepo) {
    this.deliveryService = deliveryService;
    this.receiptCoordinator = receiptCoordinator;
    this.ledgerRepo = ledgerRepo;
    this.receiptRepo = receiptRepo;
    this.eventRepo = eventRepo;
  }

  public record DeliveryHttpRequest(String operationNo, String reportNo, Integer revisionNo,
      String deliveryType, JsonNode payload) {}

  @PostMapping
  @Operation(summary = "发起投递", description = "相同操作号+相同载荷返回原结果；相同操作号+不同载荷返回 409 冲突；"
      + "不同操作号+同报告修订+相同摘要记录重复到达而不重复应用")
  public ResponseEntity<DeliveryService.DeliveryResult> deliver(
      @RequestBody DeliveryHttpRequest request) {
    DeliveryService.DeliveryResult result = deliveryService.deliver(
        new DeliveryService.DeliveryRequest(request.operationNo(), request.reportNo(),
            request.revisionNo(), request.deliveryType(), request.payload()));
    if (OperationStatus.CONFLICT.name().equals(result.status())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
    return ResponseEntity.ok(result);
  }

  @GetMapping("/{operationNo}")
  @Operation(summary = "查询操作状态", description = "操作账 + 回执 + 关联事件")
  public Map<String, Object> status(@PathVariable String operationNo) {
    OperationLedger operation = ledgerRepo.findById(operationNo)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_OPERATION", "操作号不存在: " + operationNo));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("operationNo", operation.getOperationNo());
    view.put("status", operation.getStatus());
    view.put("payloadSha256", operation.getPayloadSha256());
    view.put("reportNo", operation.getReportNo());
    view.put("revisionNo", operation.getRevisionNo());
    view.put("deliveryType", operation.getDeliveryType());
    view.put("targetEndpoint", operation.getTargetEndpoint());
    view.put("duplicateOf", operation.getDuplicateOf());
    view.put("createdAt", operation.getCreatedAt());
    view.put("updatedAt", operation.getUpdatedAt());
    view.put("receipts", receiptRepo.findByOperationNoOrderByIssuedAt(operationNo).stream()
        .map(r -> Map.of("receiptId", r.getReceiptId(), "receiptType", r.getReceiptType(),
            "issuer", r.getIssuer(), "issuedAt", r.getIssuedAt().toString()))
        .toList());
    view.put("events", eventRepo.findByOperationNoOrderByEventSeq(operationNo).stream()
        .map(DeliveryController::eventView).toList());
    return view;
  }

  @PostMapping("/{operationNo}/retry")
  @Operation(summary = "未知重试", description = "回执协调器：先按原操作号查询目标状态，再决定是否以原操作号重试；绝不新建操作")
  public ResponseEntity<DeliveryService.DeliveryResult> retry(@PathVariable String operationNo) {
    DeliveryService.DeliveryResult result = receiptCoordinator.resolveUnknown(operationNo);
    if (OperationStatus.CONFLICT.name().equals(result.status())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
    return ResponseEntity.ok(result);
  }

  static Map<String, Object> eventView(BusinessEvent event) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("eventSeq", event.getEventSeq());
    view.put("eventType", event.getEventType());
    view.put("occurredAt", event.getOccurredAt().toString());
    view.put("payload", event.getPayload());
    return view;
  }
}
