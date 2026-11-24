package com.foodsafety.exchange.domain;

/** 报告修订的业务状态机（入站侧）。 */
public enum RevisionStatus {
  /** 解析失败（结构、签名、附件引用等不符合容器固定规范） */
  PARSE_FAILED,
  /** 业务拒绝（单位、检出限、结论词表等语义校验未通过） */
  BUSINESS_REJECTED,
  /** 已受理（进入报告链，成为或曾经成为链头） */
  ACCEPTED,
  /** 被后续修订取代 */
  SUPERSEDED,
  /** 隔离（缺少 supersedes 的并行结论、未链接到链头的分叉等） */
  QUARANTINED
}
