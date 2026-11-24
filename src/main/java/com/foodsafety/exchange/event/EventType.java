package com.foodsafety.exchange.event;

/** 不可变业务事件账的事件类型。 */
public enum EventType {
  /** 容器首次到达，已写入不可变入站库 */
  INBOUND_RECEIVED,
  /** 相同容器号+相同载荷的重传（入站库不重复写入） */
  CONTAINER_RETRANSMITTED,
  /** 相同容器号但载荷摘要不一致 */
  CONTAINER_CONFLICT,
  /** 解析失败（签名、结构、附件引用、未知规范版本） */
  PARSE_FAILED,
  /** 业务拒绝（单位、检出限、结论等语义校验未通过） */
  BUSINESS_REJECTED,
  /** 修订被受理，进入报告链 */
  REPORT_ACCEPTED,
  /** 修订被后续修订取代 */
  REPORT_SUPERSEDED,
  /** 并行结论/分叉被隔离 */
  QUARANTINED,
  /** 较旧修订晚到，不覆盖已接受的新修订 */
  LATE_REVISION_IGNORED,
  /** 同报告修订且相同摘要的重复到达，不重复应用 */
  DUPLICATE_ARRIVAL,
  /** 同修订号但内容摘要不同 */
  REVISION_CONFLICT,
  /** 投递操作已登记（操作号+载荷摘要） */
  DELIVERY_REQUESTED,
  /** 目标端已受理 */
  DELIVERY_ACCEPTED,
  /** 投递超时，结果未知 */
  DELIVERY_UNKNOWN,
  /** 未知后经按原操作号查询确认 */
  DELIVERY_CONFIRMED,
  /** 相同操作号不同载荷（或目标端冲突） */
  DELIVERY_CONFLICT,
  /** 回执已登记 */
  RECEIPT_RECORDED,
  /** 投影已按事件账重建 */
  PROJECTION_REBUILT
}
