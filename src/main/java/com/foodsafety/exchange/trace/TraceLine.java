package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 生产线：工厂下属，成品批次在此生产。 */
@Entity
@Table(name = "trace_line")
public class TraceLine {

  @Id
  @Column(name = "line_id")
  private String lineId;

  @Column(name = "factory_id", nullable = false)
  private String factoryId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceLine() {}

  public TraceLine(String lineId, String factoryId, String name) {
    this.lineId = lineId;
    this.factoryId = factoryId;
    this.name = name;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getLineId() { return lineId; }
  public String getFactoryId() { return factoryId; }
  public String getName() { return name; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
