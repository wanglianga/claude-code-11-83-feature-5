package com.foodsafety.exchange.event;

/** 事件关联的五类身份键（可空，按事件类型取用）。 */
public record EventKeys(
    String containerId, String sampleKey, String reportNo, Integer revisionNo, String operationNo) {

  public static EventKeys none() {
    return new EventKeys(null, null, null, null, null);
  }

  public static EventKeys container(String containerId) {
    return new EventKeys(containerId, null, null, null, null);
  }

  public static EventKeys revision(
      String containerId, String sampleKey, String reportNo, Integer revisionNo) {
    return new EventKeys(containerId, sampleKey, reportNo, revisionNo, null);
  }

  public static EventKeys operation(String operationNo, String reportNo, Integer revisionNo) {
    return new EventKeys(null, null, reportNo, revisionNo, operationNo);
  }

  public EventKeys withOperation(String operationNo) {
    return new EventKeys(containerId, sampleKey, reportNo, revisionNo, operationNo);
  }
}
