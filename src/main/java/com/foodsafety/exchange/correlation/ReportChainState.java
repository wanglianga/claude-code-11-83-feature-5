package com.foodsafety.exchange.correlation;

import com.foodsafety.exchange.domain.RevisionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 报告链状态：按报告号聚合的修订折叠结果。
 * 同一归约逻辑被实时投影、事件重放与投影重建共用，
 * 保证两条读取路线在同一事件序列上给出相同结果。
 */
public class ReportChainState {

  /** 单个修订的折叠状态。 */
  public static class Rev {
    public int revisionNo;
    public RevisionStatus status;
    public String digest;
    public String specVersion;
    public String containerId;
    public String sampleKey;
    public List<Integer> supersedes = new ArrayList<>();
    public boolean lateArrival;
    public String reason;
    public long lastEventSeq;

    /** 是否为报告链成员（曾被受理；晚到直接标记取代的不算） */
    public boolean isChainMember() {
      return status == RevisionStatus.ACCEPTED
          || (status == RevisionStatus.SUPERSEDED && !lateArrival);
    }
  }

  public final Map<Integer, Rev> revisions = new TreeMap<>();
  /** 当前链头修订号（最近被受理者），无受理修订时为 null */
  public Integer head;
  /** 分区内已应用（使状态发生变化）的事件数，即分区版本 */
  public long partitionVersion;
  /** 分区内见过的最大事件序列号 */
  public long lastEventSeq;

  public Rev revOrNew(int revisionNo) {
    return revisions.computeIfAbsent(revisionNo, no -> {
      Rev rev = new Rev();
      rev.revisionNo = no;
      return rev;
    });
  }
}
