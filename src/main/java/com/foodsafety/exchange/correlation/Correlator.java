package com.foodsafety.exchange.correlation;

import com.foodsafety.exchange.spec.ParsedEntry;
import org.springframework.stereotype.Component;

/**
 * 业务关联器：按样品键、报告号与显式 supersedes 修订关系组装报告链。
 * 不按相似内容自动合并样品；只依据显式身份键与修订关系。
 */
@Component
public class Correlator {

  public enum Outcome {
    /** 受理：进入报告链并成为链头 */
    ACCEPT,
    /** 同报告修订且相同摘要：重复到达，不重复应用 */
    DUPLICATE_ARRIVAL,
    /** 同修订号但摘要不同：内容冲突 */
    REVISION_CONFLICT,
    /** 缺少 supersedes 的并行结论 / 未链接到链头的分叉：隔离 */
    QUARANTINE,
    /** 较旧修订晚到：不覆盖已接受的新修订 */
    LATE_REVISION
  }

  public record Decision(Outcome outcome, String reason) {
    public static Decision of(Outcome outcome) {
      return new Decision(outcome, null);
    }
    public static Decision of(Outcome outcome, String reason) {
      return new Decision(outcome, reason);
    }
  }

  public Decision decide(ParsedEntry entry, ReportChainState chain) {
    ReportChainState.Rev existing = chain.revisions.get(entry.revisionNo());
    if (existing != null && existing.isChainMember()) {
      if (existing.digest != null && existing.digest.equals(entry.digest())) {
        return Decision.of(Outcome.DUPLICATE_ARRIVAL);
      }
      return Decision.of(Outcome.REVISION_CONFLICT,
          "修订 " + entry.revisionNo() + " 已在链中但内容摘要不同");
    }

    Integer head = chain.head;
    if (head == null) {
      // 链中尚无受理修订
      if (entry.revisionNo() == 1) {
        return Decision.of(Outcome.ACCEPT);
      }
      return entry.supersedes().isEmpty()
          ? Decision.of(Outcome.QUARANTINE,
              "修订 " + entry.revisionNo() + " 未声明 supersedes 关系，按并行结论隔离")
          : Decision.of(Outcome.ACCEPT); // 允许从 >1 的修订起步（更旧修订可能晚到）
    }
    if (entry.revisionNo() <= head) {
      // 本地未见的较旧修订晚到：不覆盖已接受的新修订
      return Decision.of(Outcome.LATE_REVISION, "较旧修订晚到，当前链头为 " + head);
    }
    // revisionNo > head：必须显式链接到链头
    if (entry.supersedes().isEmpty()) {
      return Decision.of(Outcome.QUARANTINE,
          "修订 " + entry.revisionNo() + " 缺少 supersedes 关系，按并行新结论隔离");
    }
    return entry.supersedes().contains(head)
        ? Decision.of(Outcome.ACCEPT)
        : Decision.of(Outcome.QUARANTINE,
            "修订 " + entry.revisionNo() + " 的 supersedes 未链接到当前链头 " + head);
  }
}
