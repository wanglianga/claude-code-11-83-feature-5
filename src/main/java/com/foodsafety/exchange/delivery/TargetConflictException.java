package com.foodsafety.exchange.delivery;

/** 目标端返回冲突（同操作号不同载荷）。 */
public class TargetConflictException extends RuntimeException {
  public TargetConflictException(String message) {
    super(message);
  }
}
