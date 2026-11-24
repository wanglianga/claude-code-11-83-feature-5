package com.foodsafety.exchange.targetsim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 模拟目标端的收件箱（与本服务状态相互独立的“远端”）。 */
@Entity
@Table(name = "target_inbox")
public class TargetInbox {

  @Id
  @Column(name = "operation_no")
  private String operationNo;

  @Column(name = "payload_sha256", nullable = false)
  private String payloadSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
  private String requestPayload;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  protected TargetInbox() {}

  public TargetInbox(String operationNo, String payloadSha256, String requestPayload, String status) {
    this.operationNo = operationNo;
    this.payloadSha256 = payloadSha256;
    this.requestPayload = requestPayload;
    this.status = status;
    this.receivedAt = OffsetDateTime.now();
  }

  public String getOperationNo() { return operationNo; }
  public String getPayloadSha256() { return payloadSha256; }
  public String getRequestPayload() { return requestPayload; }
  public String getStatus() { return status; }
  public OffsetDateTime getReceivedAt() { return receivedAt; }
}
