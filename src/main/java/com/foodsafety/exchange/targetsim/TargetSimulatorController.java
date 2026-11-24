package com.foodsafety.exchange.targetsim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.delivery.TargetGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟目标端（演示用“远端系统”）：按操作号幂等接收投递、签发回执、支持状态查询；
 * 通过故障开关可注入“提交成功但响应超时”，用于演示超时未知与协调收敛。
 */
@RestController
@RequestMapping("/target")
public class TargetSimulatorController {

  private final TargetInboxRepository inboxRepo;
  private final TargetReceiptRepository receiptRepo;
  private final FaultSwitchRepository faultRepo;
  private final TransactionTemplate txTemplate;
  private final ObjectMapper objectMapper;

  public TargetSimulatorController(TargetInboxRepository inboxRepo,
      TargetReceiptRepository receiptRepo, FaultSwitchRepository faultRepo,
      TransactionTemplate txTemplate, ObjectMapper objectMapper) {
    this.inboxRepo = inboxRepo;
    this.receiptRepo = receiptRepo;
    this.faultRepo = faultRepo;
    this.txTemplate = txTemplate;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/deliveries")
  public ResponseEntity<Map<String, Object>> receive(
      @RequestBody TargetGateway.TargetDeliveryRequest request) throws Exception {
    TargetInbox existing = inboxRepo.findById(request.operationNo()).orElse(null);
    if (existing != null) {
      if (existing.getPayloadSha256().equals(request.payloadSha256())) {
        return ResponseEntity.ok(deliveryResponse(request.operationNo()));
      }
      return ResponseEntity.status(409).body(Map.of(
          "error", "CONFLICT",
          "message", "操作号 " + request.operationNo() + " 已存在且载荷不同"));
    }

    // 先提交收件箱与回执，再（可选）延迟响应：模拟“已受理但响应丢失”
    String payloadJson = objectMapper.writeValueAsString(request);
    Boolean timeoutArmed = txTemplate.execute(status -> {
      inboxRepo.save(new TargetInbox(request.operationNo(), request.payloadSha256(),
          payloadJson, "ACCEPTED"));
      String receiptType = "DISPOSAL".equals(request.deliveryType())
          ? "DISPOSAL_CONFIRMED" : "RECEIVED";
      receiptRepo.save(new TargetReceipt("rcpt-" + request.operationNo() + "-1",
          request.operationNo(), receiptType));
      FaultSwitch fs = faultRepo.findById(1).orElseGet(() -> new FaultSwitch(1, false));
      boolean armed = fs.isTimeoutNext();
      if (armed) {
        fs.setTimeoutNext(false);
        faultRepo.save(fs);
      }
      return armed;
    });

    if (Boolean.TRUE.equals(timeoutArmed)) {
      Thread.sleep(4000); // 客户端读超时（1.5s）先触发，本响应随后被丢弃
    }
    return ResponseEntity.ok(deliveryResponse(request.operationNo()));
  }

  @GetMapping("/deliveries/{operationNo}")
  public ResponseEntity<Map<String, Object>> query(@PathVariable String operationNo) {
    TargetInbox inbox = inboxRepo.findById(operationNo).orElse(null);
    if (inbox == null) {
      return ResponseEntity.status(404).body(Map.of(
          "error", "NOT_FOUND", "message", "目标端未收到操作 " + operationNo));
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("operationNo", inbox.getOperationNo());
    view.put("status", inbox.getStatus());
    view.put("receipts", receiptViews(operationNo));
    return ResponseEntity.ok(view);
  }

  @PostMapping("/faults")
  public Map<String, Object> faults(@RequestBody Map<String, Boolean> body) {
    FaultSwitch fs = faultRepo.findById(1).orElseGet(() -> new FaultSwitch(1, false));
    fs.setTimeoutNext(Boolean.TRUE.equals(body.get("timeoutNext")));
    faultRepo.save(fs);
    return Map.of("timeoutNext", fs.isTimeoutNext());
  }

  @GetMapping("/inbox")
  public List<Map<String, Object>> inbox() {
    return inboxRepo.findAll().stream().map(row -> {
      Map<String, Object> view = new LinkedHashMap<String, Object>();
      view.put("operationNo", row.getOperationNo());
      view.put("payloadSha256", row.getPayloadSha256());
      view.put("status", row.getStatus());
      view.put("receivedAt", row.getReceivedAt().toString());
      return view;
    }).toList();
  }

  @PostMapping("/reset")
  public Map<String, Object> reset() {
    receiptRepo.deleteAll();
    inboxRepo.deleteAll();
    FaultSwitch fs = faultRepo.findById(1).orElseGet(() -> new FaultSwitch(1, false));
    fs.setTimeoutNext(false);
    faultRepo.save(fs);
    return Map.of("reset", true);
  }

  private Map<String, Object> deliveryResponse(String operationNo) {
    List<Map<String, Object>> receipts = receiptViews(operationNo);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", "ACCEPTED");
    response.put("receipt", receipts.isEmpty() ? null : receipts.get(receipts.size() - 1));
    return response;
  }

  private List<Map<String, Object>> receiptViews(String operationNo) {
    return receiptRepo.findByOperationNoOrderByIssuedAt(operationNo).stream()
        .map(r -> {
          Map<String, Object> view = new LinkedHashMap<String, Object>();
          view.put("receiptId", r.getReceiptId());
          view.put("receiptType", r.getReceiptType());
          view.put("issuedAt", r.getIssuedAt().toString());
          return view;
        }).toList();
  }
}
