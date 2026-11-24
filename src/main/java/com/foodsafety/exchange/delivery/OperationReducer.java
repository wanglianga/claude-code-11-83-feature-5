package com.foodsafety.exchange.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.domain.OperationStatus;
import com.foodsafety.exchange.event.BusinessEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作状态归约器：从事件序列折叠出某个操作号的状态。
 * 事件重放与投影（操作账）一致性核验共用。
 */
public final class OperationReducer {

  private static final ObjectMapper OM = new ObjectMapper();

  private OperationReducer() {}

  /** 操作号的折叠状态。 */
  public static class OperationState {
    public String operationNo;
    public OperationStatus status;
    public String payloadHash;
    public String reportNo;
    public Integer revisionNo;
    public String deliveryType;
    public String duplicateOf;
    public final List<Map<String, Object>> receipts = new ArrayList<>();
    public long lastEventSeq;
  }

  public static OperationState fold(String operationNo, List<BusinessEvent> events) {
    OperationState state = new OperationState();
    state.operationNo = operationNo;
    for (BusinessEvent event : events) {
      apply(state, event);
    }
    return state;
  }

  public static void apply(OperationState state, BusinessEvent event) {
    state.lastEventSeq = Math.max(state.lastEventSeq, event.getEventSeq());
    JsonNode payload = read(event.getPayload());
    switch (event.type()) {
      case DELIVERY_REQUESTED -> {
        state.status = OperationStatus.PENDING;
        state.payloadHash = text(payload, "payloadSha256");
        state.reportNo = text(payload, "reportNo");
        state.revisionNo = payload.hasNonNull("revisionNo") ? payload.get("revisionNo").asInt() : null;
        state.deliveryType = text(payload, "deliveryType");
      }
      case DELIVERY_ACCEPTED, DELIVERY_CONFIRMED -> state.status = OperationStatus.ACCEPTED;
      case DELIVERY_UNKNOWN -> state.status = OperationStatus.UNKNOWN;
      case DELIVERY_CONFLICT -> {
        // 仅目标端冲突改变操作状态；本地“同号不同载荷”的尝试只记录事件，原操作结果不变
        if ("TARGET".equals(text(payload, "source"))) {
          state.status = OperationStatus.CONFLICT;
        }
      }
      case DUPLICATE_ARRIVAL -> {
        state.status = OperationStatus.DUPLICATE;
        state.duplicateOf = text(payload, "duplicateOf");
      }
      case RECEIPT_RECORDED -> {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptId", text(payload, "receiptId"));
        receipt.put("receiptType", text(payload, "receiptType"));
        receipt.put("issuer", text(payload, "issuer"));
        receipt.put("issuedAt", text(payload, "issuedAt"));
        state.receipts.add(receipt);
      }
      default -> {
        // 其他事件不影响操作状态
      }
    }
  }

  private static JsonNode read(String json) {
    try {
      return OM.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("事件载荷不是合法 JSON", e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
