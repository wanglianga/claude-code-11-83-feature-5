package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 配方投料记录：成品批次在 usedFrom..usedTo 使用某原料批次。
 * raw_batch_id 为空表示批次记录缺失——这会让候选范围扩张（无法锁定到具体批次）。
 */
@Entity
@Table(name = "trace_usage")
public class TraceUsage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "usage_id")
  private Long usageId;

  @Column(name = "product_batch_id", nullable = false)
  private String productBatchId;

  @Column(name = "raw_batch_id")
  private String rawBatchId;

  @Column(name = "ingredient_code", nullable = false)
  private String ingredientCode;

  @Column(name = "used_from")
  private OffsetDateTime usedFrom;

  @Column(name = "used_to")
  private OffsetDateTime usedTo;

  @Column(name = "quantity_kg")
  private BigDecimal quantityKg;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceUsage() {}

  public TraceUsage(String productBatchId, String rawBatchId, String ingredientCode,
      OffsetDateTime usedFrom, OffsetDateTime usedTo, BigDecimal quantityKg) {
    this.productBatchId = productBatchId;
    this.rawBatchId = rawBatchId;
    this.ingredientCode = ingredientCode;
    this.usedFrom = usedFrom;
    this.usedTo = usedTo;
    this.quantityKg = quantityKg;
    this.recordedAt = OffsetDateTime.now();
  }

  public Long getUsageId() { return usageId; }
  public String getProductBatchId() { return productBatchId; }
  public String getRawBatchId() { return rawBatchId; }
  public String getIngredientCode() { return ingredientCode; }
  public OffsetDateTime getUsedFrom() { return usedFrom; }
  public OffsetDateTime getUsedTo() { return usedTo; }
  public BigDecimal getQuantityKg() { return quantityKg; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
