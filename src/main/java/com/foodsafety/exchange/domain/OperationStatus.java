package com.foodsafety.exchange.domain;

/** 投递操作的状态机（出站侧）。 */
public enum OperationStatus {
  /** 已登记，等待目标端确认 */
  PENDING,
  /** 目标端已受理 */
  ACCEPTED,
  /** 超时未知：已发送但结果未知，须先按原操作号查询目标状态 */
  UNKNOWN,
  /** 冲突：相同操作号提交了不同载荷 */
  CONFLICT,
  /** 重复到达：不同操作号但同报告修订且相同摘要，不重复应用 */
  DUPLICATE,
  /** 失败（目标端明确拒绝等） */
  FAILED
}
