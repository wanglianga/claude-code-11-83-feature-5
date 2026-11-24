package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 原料批次：供应商、采购区间、到货区间与参考化学指纹。
 * 采购/到货区间用于与投料时间做可达性求值；指纹用于相容度求值。
 */
@Entity
@Table(name = "trace_raw_batch")
public class TraceRawBatch {

  @Id
  @Column(name = "raw_batch_id")
  private String rawBatchId;

  @Column(name = "ingredient_code", nullable = false)
  private String ingredientCode;

  @Column(name = "supplier_id", nullable = false)
  private String supplierId;

  @Column(name = "purchase_from")
  private OffsetDateTime purchaseFrom;

  @Column(name = "purchase_to")
  private OffsetDateTime purchaseTo;

  @Column(name = "arrival_from")
  private OffsetDateTime arrivalFrom;

  @Column(name = "arrival_to")
  private OffsetDateTime arrivalTo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fingerprint", nullable = false, columnDefinition = "jsonb")
  private String fingerprint;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceRawBatch() {}

  public TraceRawBatch(String rawBatchId, String ingredientCode, String supplierId,
      OffsetDateTime purchaseFrom, OffsetDateTime purchaseTo, OffsetDateTime arrivalFrom,
      OffsetDateTime arrivalTo, String fingerprintJson) {
    this.rawBatchId = rawBatchId;
    this.ingredientCode = ingredientCode;
    this.supplierId = supplierId;
    this.purchaseFrom = purchaseFrom;
    this.purchaseTo = purchaseTo;
    this.arrivalFrom = arrivalFrom;
    this.arrivalTo = arrivalTo;
    this.fingerprint = fingerprintJson == null ? "{}" : fingerprintJson;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getRawBatchId() { return rawBatchId; }
  public String getIngredientCode() { return ingredientCode; }
  public String getSupplierId() { return supplierId; }
  public OffsetDateTime getPurchaseFrom() { return purchaseFrom; }
  public OffsetDateTime getPurchaseTo() { return purchaseTo; }
  public OffsetDateTime getArrivalFrom() { return arrivalFrom; }
  public OffsetDateTime getArrivalTo() { return arrivalTo; }
  public String getFingerprint() { return fingerprint; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
