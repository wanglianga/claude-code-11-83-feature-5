package com.foodsafety.exchange.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 操作账：一行 = 一个投递操作号（一次目标端写入尝试的幂等键）。 */
@Entity
@Table(name = "operation_ledger")
public class OperationLedger {

  @Id
  @Column(name = "operation_no")
  private String operationNo;

  @Column(name = "payload_sha256", nullable = false)
  private String payloadSha256;

  @Column(name = "target_endpoint", nullable = false)
  private String targetEndpoint;

  @Column(name = "delivery_type", nullable = false)
  private String deliveryType;

  @Column(name = "report_no", nullable = false)
  private String reportNo;

  @Column(name = "revision_no", nullable = false)
  private int revisionNo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
  private String requestPayload;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "duplicate_of")
  private String duplicateOf;

  @Column(name = "last_event_seq", nullable = false)
  private long lastEventSeq;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected OperationLedger() {}

  public OperationLedger(String operationNo, String payloadSha256, String targetEndpoint,
      String deliveryType, String reportNo, int revisionNo, String requestPayload,
      String status, long lastEventSeq) {
    this.operationNo = operationNo;
    this.payloadSha256 = payloadSha256;
    this.targetEndpoint = targetEndpoint;
    this.deliveryType = deliveryType;
    this.reportNo = reportNo;
    this.revisionNo = revisionNo;
    this.requestPayload = requestPayload;
    this.status = status;
    this.lastEventSeq = lastEventSeq;
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = this.createdAt;
  }

  public String getOperationNo() { return operationNo; }
  public String getPayloadSha256() { return payloadSha256; }
  public String getTargetEndpoint() { return targetEndpoint; }
  public String getDeliveryType() { return deliveryType; }
  public String getReportNo() { return reportNo; }
  public int getRevisionNo() { return revisionNo; }
  public String getRequestPayload() { return requestPayload; }
  public String getStatus() { return status; }
  public String getDuplicateOf() { return duplicateOf; }
  public long getLastEventSeq() { return lastEventSeq; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  public void updateStatus(String status, long lastEventSeq) {
    this.status = status;
    this.lastEventSeq = lastEventSeq;
    this.updatedAt = OffsetDateTime.now();
  }

  public void markDuplicate(String duplicateOf, long lastEventSeq) {
    this.duplicateOf = duplicateOf;
    updateStatus("DUPLICATE", lastEventSeq);
  }
}
