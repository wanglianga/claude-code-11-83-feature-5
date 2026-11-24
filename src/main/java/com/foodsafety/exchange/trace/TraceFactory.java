package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 生产工厂：污染源头推导中的最上层组织节点。 */
@Entity
@Table(name = "trace_factory")
public class TraceFactory {

  @Id
  @Column(name = "factory_id")
  private String factoryId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "region")
  private String region;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceFactory() {}

  public TraceFactory(String factoryId, String name, String region) {
    this.factoryId = factoryId;
    this.name = name;
    this.region = region;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getFactoryId() { return factoryId; }
  public String getName() { return name; }
  public String getRegion() { return region; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
