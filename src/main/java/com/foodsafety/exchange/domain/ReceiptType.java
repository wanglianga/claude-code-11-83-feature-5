package com.foodsafety.exchange.domain;

/** 回执类型：区分“仅表示收到”与“确认处置”。 */
public enum ReceiptType {
  /** 仅表示目标端收到 */
  RECEIVED,
  /** 目标端确认已完成处置 */
  DISPOSAL_CONFIRMED
}
