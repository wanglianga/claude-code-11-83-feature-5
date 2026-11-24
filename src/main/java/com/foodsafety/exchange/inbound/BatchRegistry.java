package com.foodsafety.exchange.inbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 发送批次：批次 -> 规范版本（容器创建时固定的规范）。 */
@Entity
@Table(name = "batch_registry")
public class BatchRegistry {

  @Id
  @Column(name = "batch_id")
  private String batchId;

  @Column(name = "spec_version", nullable = false)
  private String specVersion;

  @Column(name = "effective_from", nullable = false)
  private OffsetDateTime effectiveFrom;

  protected BatchRegistry() {}

  public String getBatchId() { return batchId; }
  public String getSpecVersion() { return specVersion; }
  public OffsetDateTime getEffectiveFrom() { return effectiveFrom; }
}
