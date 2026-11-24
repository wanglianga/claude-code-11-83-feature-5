package com.foodsafety.exchange.replay;

import com.foodsafety.exchange.correlation.ReportChainState;
import com.foodsafety.exchange.correlation.RevisionReducer;
import com.foodsafety.exchange.delivery.OperationReducer;
import com.foodsafety.exchange.event.BusinessEvent;
import com.foodsafety.exchange.event.BusinessEventRepository;
import com.foodsafety.exchange.projection.Projector;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 事件重放：按查询时点（事件序列号或时间）折叠事件账，
 * 直接给出历史时点的报告链与操作状态。需要扫描事件并管理重放窗口。
 */
@Service
public class ReplayService {

  private final BusinessEventRepository eventRepo;
  private final Projector projector;

  public ReplayService(BusinessEventRepository eventRepo, Projector projector) {
    this.eventRepo = eventRepo;
    this.projector = projector;
  }

  public record RevisionView(int revisionNo, String status, String digest, String specVersion,
      String containerId, String sampleKey, List<Integer> supersedes, boolean lateArrival,
      String reason, long eventSeq) {}

  public record ReportChainView(String reportNo, String sampleKey, Integer head,
      long partitionVersion, long asOfSeq, int eventsScanned, List<RevisionView> revisions) {}

  public record OperationReplayView(String operationNo, String status, String payloadHash,
      String duplicateOf, List<Map<String, Object>> receipts, long asOfSeq, int eventsScanned) {}

  public record VerifyView(String reportNo, boolean consistent, List<String> differences,
      Integer replayedHead, Integer projectionHead, long replayedPartitionVersion,
      long projectionPartitionVersion) {}

  /** 解析重放窗口上界：显式序列号优先，其次时间点（取该时点前最大序列号），默认当前。 */
  public long resolveSeq(Long asOfSeq, OffsetDateTime asOfTime) {
    if (asOfSeq != null) {
      return asOfSeq;
    }
    if (asOfTime != null) {
      return eventRepo.maxSeqAt(asOfTime).orElse(0L);
    }
    return eventRepo.maxSeq().orElse(0L);
  }

  public ReportChainView replayReport(String reportNo, Long asOfSeq, OffsetDateTime asOfTime) {
    long seq = resolveSeq(asOfSeq, asOfTime);
    List<BusinessEvent> events =
        eventRepo.findByReportNoAndEventSeqLessThanEqualOrderByEventSeq(reportNo, seq);
    ReportChainState state = new ReportChainState();
    events.forEach(event -> RevisionReducer.apply(state, event));
    return toView(reportNo, state, seq, events.size());
  }

  public OperationReplayView replayOperation(String operationNo, Long asOfSeq, OffsetDateTime asOfTime) {
    long seq = resolveSeq(asOfSeq, asOfTime);
    List<BusinessEvent> events =
        eventRepo.findByOperationNoAndEventSeqLessThanEqualOrderByEventSeq(operationNo, seq);
    OperationReducer.OperationState state = OperationReducer.fold(operationNo, events);
    return new OperationReplayView(operationNo,
        state.status == null ? null : state.status.name(), state.payloadHash, state.duplicateOf,
        state.receipts, seq, events.size());
  }

  /** 核验：事件重放（当前时点）与版本化投影是否一致。 */
  public VerifyView verifyReport(String reportNo) {
    long seq = resolveSeq(null, null);
    List<BusinessEvent> events =
        eventRepo.findByReportNoAndEventSeqLessThanEqualOrderByEventSeq(reportNo, seq);
    ReportChainState replayed = new ReportChainState();
    events.forEach(event -> RevisionReducer.apply(replayed, event));
    ReportChainState projected = projector.loadState(reportNo);

    List<String> differences = new ArrayList<>();
    for (int revisionNo : union(replayed, projected)) {
      ReportChainState.Rev a = replayed.revisions.get(revisionNo);
      ReportChainState.Rev b = projected.revisions.get(revisionNo);
      if (a == null || b == null) {
        differences.add("修订 " + revisionNo + " 仅存在于" + (a == null ? "投影" : "重放"));
        continue;
      }
      if (a.status != b.status) {
        differences.add("修订 " + revisionNo + " 状态: 重放=" + a.status + " 投影=" + b.status);
      }
      if (!safeEquals(a.digest, b.digest)) {
        differences.add("修订 " + revisionNo + " 摘要不一致");
      }
      if (!safeEquals(a.specVersion, b.specVersion)) {
        differences.add("修订 " + revisionNo + " 规范版本: 重放=" + a.specVersion
            + " 投影=" + b.specVersion);
      }
    }
    if (!safeEquals(replayed.head, projected.head)) {
      differences.add("链头: 重放=" + replayed.head + " 投影=" + projected.head);
    }
    if (replayed.partitionVersion != projected.partitionVersion) {
      differences.add("分区版本: 重放=" + replayed.partitionVersion
          + " 投影=" + projected.partitionVersion);
    }
    return new VerifyView(reportNo, differences.isEmpty(), differences,
        replayed.head, projected.head, replayed.partitionVersion, projected.partitionVersion);
  }

  private ReportChainView toView(String reportNo, ReportChainState state, long seq, int scanned) {
    List<RevisionView> revisions = new ArrayList<>();
    String sampleKey = null;
    for (ReportChainState.Rev rev : state.revisions.values()) {
      if (sampleKey == null) {
        sampleKey = rev.sampleKey;
      }
      revisions.add(new RevisionView(rev.revisionNo, rev.status.name(), rev.digest,
          rev.specVersion, rev.containerId, rev.sampleKey, rev.supersedes, rev.lateArrival,
          rev.reason, rev.lastEventSeq));
    }
    return new ReportChainView(reportNo, sampleKey, state.head, state.partitionVersion, seq,
        scanned, revisions);
  }

  private static List<Integer> union(ReportChainState a, ReportChainState b) {
    Map<Integer, Boolean> all = new TreeMap<>();
    a.revisions.keySet().forEach(k -> all.put(k, true));
    b.revisions.keySet().forEach(k -> all.put(k, true));
    return List.copyOf(all.keySet());
  }

  private static boolean safeEquals(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }
}
