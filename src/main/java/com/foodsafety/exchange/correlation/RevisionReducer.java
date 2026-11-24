package com.foodsafety.exchange.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.domain.RevisionStatus;
import com.foodsafety.exchange.event.BusinessEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告链归约器：将单个事件折叠进报告链状态。
 * 实时投影、事件重放、投影重建共用此唯一归约逻辑，
 * 是“两条路线在同一事件序列上给出相同结果”的保证点。
 */
public final class RevisionReducer {

  private static final ObjectMapper OM = new ObjectMapper();

  private RevisionReducer() {}

  public static void apply(ReportChainState state, BusinessEvent event) {
    long seq = event.getEventSeq();
    JsonNode payload = read(event.getPayload());
    Integer revisionNo = event.getRevisionNo();

    switch (event.type()) {
      case REPORT_ACCEPTED -> {
        ReportChainState.Rev rev = state.revOrNew(revisionNo);
        fill(rev, payload);
        rev.status = RevisionStatus.ACCEPTED;
        rev.lateArrival = false;
        rev.reason = null;
        rev.lastEventSeq = seq;
        state.head = revisionNo;
        state.partitionVersion++;
        state.lastEventSeq = seq;
      }
      case REPORT_SUPERSEDED -> {
        ReportChainState.Rev rev = state.revisions.get(revisionNo);
        if (rev != null && rev.status == RevisionStatus.ACCEPTED) {
          rev.status = RevisionStatus.SUPERSEDED;
          rev.lastEventSeq = seq;
          state.partitionVersion++;
          state.lastEventSeq = seq;
        }
      }
      case BUSINESS_REJECTED -> applyIfNotChainMember(state, revisionNo, payload, seq,
          RevisionStatus.BUSINESS_REJECTED, false, text(payload, "reason"));
      case QUARANTINED -> applyIfNotChainMember(state, revisionNo, payload, seq,
          RevisionStatus.QUARANTINED, false, text(payload, "reason"));
      case LATE_REVISION_IGNORED -> applyIfNotChainMember(state, revisionNo, payload, seq,
          RevisionStatus.SUPERSEDED, true, text(payload, "reason"));
      default -> {
        // 其余事件不改变报告修订状态（lastEventSeq 仅在状态变更时推进，保证实时投影与重建一致）
      }
    }
  }

  private static void applyIfNotChainMember(ReportChainState state, int revisionNo,
      JsonNode payload, long seq, RevisionStatus status, boolean lateArrival, String reason) {
    ReportChainState.Rev existing = state.revisions.get(revisionNo);
    if (existing != null && existing.isChainMember()) {
      return; // 链成员不被拒绝/隔离类事件覆盖
    }
    ReportChainState.Rev rev = state.revOrNew(revisionNo);
    fill(rev, payload);
    rev.status = status;
    rev.lateArrival = lateArrival;
    rev.reason = reason;
    rev.lastEventSeq = seq;
    state.partitionVersion++;
    state.lastEventSeq = seq;
  }

  private static void fill(ReportChainState.Rev rev, JsonNode payload) {
    rev.digest = text(payload, "digest");
    rev.specVersion = text(payload, "specVersion");
    rev.containerId = text(payload, "containerId");
    rev.sampleKey = text(payload, "sampleKey");
    rev.supersedes = intList(payload, "supersedes");
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

  private static List<Integer> intList(JsonNode node, String field) {
    List<Integer> result = new ArrayList<>();
    JsonNode array = node.get(field);
    if (array != null && array.isArray()) {
      array.forEach(item -> result.add(item.asInt()));
    }
    return result;
  }
}
