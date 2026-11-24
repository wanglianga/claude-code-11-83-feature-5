package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 样品账：样品键 -> 成品批次（生产线/配方由批次与投料记录关联）。 */
@Entity
@Table(name = "trace_sample")
public class TraceSample {

  @Id
  @Column(name = "sample_key")
  private String sampleKey;

  @Column(name = "product_batch_id", nullable = false)
  private String productBatchId;

  @Column(name = "sampled_at", nullable = false)
  private OffsetDateTime sampledAt;

  @Column(name = "note")
  private String note;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceSample() {}

  public TraceSample(String sampleKey, String productBatchId, OffsetDateTime sampledAt, String note) {
    this.sampleKey = sampleKey;
    this.productBatchId = productBatchId;
    this.sampledAt = sampledAt;
    this.note = note;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getSampleKey() { return sampleKey; }
  public String getProductBatchId() { return productBatchId; }
  public OffsetDateTime getSampledAt() { return sampledAt; }
  public String getNote() { return note; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
