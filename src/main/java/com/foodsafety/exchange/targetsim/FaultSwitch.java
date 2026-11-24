package com.foodsafety.exchange.targetsim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 故障注入开关：武装后，下一次投递在目标端提交成功但响应超时。 */
@Entity
@Table(name = "fault_switch")
public class FaultSwitch {

  @Id
  @Column(name = "id")
  private int id;

  @Column(name = "timeout_next", nullable = false)
  private boolean timeoutNext;

  protected FaultSwitch() {}

  public FaultSwitch(int id, boolean timeoutNext) {
    this.id = id;
    this.timeoutNext = timeoutNext;
  }

  public boolean isTimeoutNext() { return timeoutNext; }

  public void setTimeoutNext(boolean timeoutNext) { this.timeoutNext = timeoutNext; }
}
