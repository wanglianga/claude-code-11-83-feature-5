package com.foodsafety.exchange.delivery;

/** 目标端不可达 / 超时（结果未知）。 */
public class TargetUnavailableException extends RuntimeException {
  public TargetUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
