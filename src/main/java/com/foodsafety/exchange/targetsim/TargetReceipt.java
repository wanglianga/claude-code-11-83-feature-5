package com.foodsafety.exchange.targetsim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 模拟目标端签发的回执。 */
@Entity
@Table(name = "target_receipt")
public class TargetReceipt {

  @Id
  @Column(name = "receipt_id")
  private String receiptId;

  @Column(name = "operation_no", nullable = false)
  private String operationNo;

  @Column(name = "receipt_type", nullable = false)
  private String receiptType;

  @Column(name = "issued_at", nullable = false)
  private OffsetDateTime issuedAt;

  protected TargetReceipt() {}

  public TargetReceipt(String receiptId, String operationNo, String receiptType) {
    this.receiptId = receiptId;
    this.operationNo = operationNo;
    this.receiptType = receiptType;
    this.issuedAt = OffsetDateTime.now();
  }

  public String getReceiptId() { return receiptId; }
  public String getOperationNo() { return operationNo; }
  public String getReceiptType() { return receiptType; }
  public OffsetDateTime getIssuedAt() { return issuedAt; }
}
