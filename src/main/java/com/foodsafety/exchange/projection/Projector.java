package com.foodsafety.exchange.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.correlation.ReportChainState;
import com.foodsafety.exchange.correlation.RevisionReducer;
import com.foodsafety.exchange.domain.RevisionStatus;
import com.foodsafety.exchange.event.BusinessEvent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投影器：把事件账中的修订事件折叠进版本化当前投影。
 * 与事件重放、投影重建共用 {@link RevisionReducer}，保证结果一致。
 */
@Service
public class Projector {

  private static final ObjectMapper OM = new ObjectMapper();

  private final ReportRevisionProjectionRepository revisionRepo;
  private final ReportHeadProjectionRepository headRepo;

  public Projector(ReportRevisionProjectionRepository revisionRepo,
      ReportHeadProjectionRepository headRepo) {
    this.revisionRepo = revisionRepo;
    this.headRepo = headRepo;
  }

  /** 从投影表加载报告链当前状态。 */
  public ReportChainState loadState(String reportNo) {
    ReportChainState state = new ReportChainState();
    for (ReportRevisionProjection row : revisionRepo.findByReportNoOrderByRevisionNo(reportNo)) {
      ReportChainState.Rev rev = state.revOrNew(row.getRevisionNo());
      rev.status = RevisionStatus.valueOf(row.getStatus());
      rev.digest = row.getDigest();
      rev.specVersion = row.getSpecVersion();
      rev.containerId = row.getContainerId();
      rev.sampleKey = row.getSampleKey();
      rev.supersedes = parseSupersedes(row.getSupersedes());
      rev.lateArrival = row.isLateArrival();
      rev.reason = row.getReason();
      rev.lastEventSeq = row.getAppliedEventSeq();
    }
    headRepo.findById(reportNo).ifPresent(head -> {
      state.head = head.getHeadRevision();
      state.partitionVersion = head.getPartitionVersion();
      state.lastEventSeq = head.getLastEventSeq();
    });
    return state;
  }

  /** 应用单个事件并持久化投影（与事件写入同事务）。 */
  @Transactional(propagation = Propagation.REQUIRED)
  public void apply(BusinessEvent event) {
    if (event.getReportNo() == null || event.getRevisionNo() == null) {
      return;
    }
    ReportChainState state = loadState(event.getReportNo());
    RevisionReducer.apply(state, event);
    persistState(event.getReportNo(), state);
  }

  /** 将报告链状态整体写入投影（实时推进与重建共用）。 */
  @Transactional(propagation = Propagation.REQUIRED)
  public void persistState(String reportNo, ReportChainState state) {
    OffsetDateTime now = OffsetDateTime.now();
    for (ReportChainState.Rev rev : state.revisions.values()) {
      ReportRevisionProjection row = revisionRepo
          .findByReportNoAndRevisionNo(reportNo, rev.revisionNo)
          .orElse(new ReportRevisionProjection());
      row.apply(reportNo, rev.revisionNo, rev.sampleKey, rev.status.name(), rev.digest,
          rev.specVersion, toJsonArray(rev.supersedes), rev.containerId, rev.lateArrival,
          rev.reason, rev.lastEventSeq, now);
      revisionRepo.save(row);
    }
    if (!state.revisions.isEmpty()) {
      ReportHeadProjection head = headRepo.findById(reportNo)
          .orElse(new ReportHeadProjection(reportNo));
      String sampleKey = state.head != null
          ? state.revisions.get(state.head).sampleKey
          : state.revisions.values().iterator().next().sampleKey;
      String headStatus = state.head != null
          ? state.revisions.get(state.head).status.name() : null;
      head.update(sampleKey, state.head, headStatus, state.partitionVersion,
          state.lastEventSeq, now);
      headRepo.save(head);
    }
  }

  private static List<Integer> parseSupersedes(String json) {
    try {
      List<Integer> result = new ArrayList<>();
      OM.readTree(json).forEach(node -> result.add(node.asInt()));
      return result;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String toJsonArray(List<Integer> values) {
    try {
      return OM.writeValueAsString(values == null ? List.of() : values);
    } catch (Exception e) {
      return "[]";
    }
  }
}
