package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 成品批次：生产线在 [productionStart, productionEnd] 内生产的批次。 */
@Entity
@Table(name = "trace_product_batch")
public class TraceProductBatch {

  @Id
  @Column(name = "product_batch_id")
  private String productBatchId;

  @Column(name = "line_id", nullable = false)
  private String lineId;

  @Column(name = "product_code", nullable = false)
  private String productCode;

  @Column(name = "production_start", nullable = false)
  private OffsetDateTime productionStart;

  @Column(name = "production_end", nullable = false)
  private OffsetDateTime productionEnd;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceProductBatch() {}

  public TraceProductBatch(String productBatchId, String lineId, String productCode,
      OffsetDateTime productionStart, OffsetDateTime productionEnd) {
    this.productBatchId = productBatchId;
    this.lineId = lineId;
    this.productCode = productCode;
    this.productionStart = productionStart;
    this.productionEnd = productionEnd;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getProductBatchId() { return productBatchId; }
  public String getLineId() { return lineId; }
  public String getProductCode() { return productCode; }
  public OffsetDateTime getProductionStart() { return productionStart; }
  public OffsetDateTime getProductionEnd() { return productionEnd; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
