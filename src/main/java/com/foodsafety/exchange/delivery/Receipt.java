package com.foodsafety.exchange.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 回执：类型区分“仅收到”与“确认处置”。 */
@Entity
@Table(name = "receipt")
public class Receipt {

  @Id
  @Column(name = "receipt_id")
  private String receiptId;

  @Column(name = "operation_no", nullable = false)
  private String operationNo;

  @Column(name = "receipt_type", nullable = false)
  private String receiptType;

  @Column(name = "issuer", nullable = false)
  private String issuer;

  @Column(name = "detail")
  private String detail;

  @Column(name = "issued_at", nullable = false)
  private OffsetDateTime issuedAt;

  @Column(name = "recorded_event_seq", nullable = false)
  private long recordedEventSeq;

  protected Receipt() {}

  public Receipt(String receiptId, String operationNo, String receiptType, String issuer,
      String detail, OffsetDateTime issuedAt, long recordedEventSeq) {
    this.receiptId = receiptId;
    this.operationNo = operationNo;
    this.receiptType = receiptType;
    this.issuer = issuer;
    this.detail = detail;
    this.issuedAt = issuedAt;
    this.recordedEventSeq = recordedEventSeq;
  }

  public String getReceiptId() { return receiptId; }
  public String getOperationNo() { return operationNo; }
  public String getReceiptType() { return receiptType; }
  public String getIssuer() { return issuer; }
  public String getDetail() { return detail; }
  public OffsetDateTime getIssuedAt() { return issuedAt; }
  public long getRecordedEventSeq() { return recordedEventSeq; }
}
