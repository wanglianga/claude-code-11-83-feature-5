package com.foodsafety.exchange.inbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 入站库信封：不可变保存字节摘要、签名、发送方、规范版本与接收时间。
 * 该表由数据库触发器禁止 UPDATE/DELETE。
 */
@Entity
@Table(name = "inbound_envelope")
public class InboundEnvelope {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "container_id", nullable = false)
  private String containerId;

  @Column(name = "batch_id", nullable = false)
  private String batchId;

  @Column(name = "sender", nullable = false)
  private String sender;

  @Column(name = "spec_version", nullable = false)
  private String specVersion;

  @Column(name = "payload_sha256", nullable = false)
  private String payloadSha256;

  @Column(name = "signature", nullable = false)
  private String signature;

  @Column(name = "signature_valid", nullable = false)
  private boolean signatureValid;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attachments", nullable = false, columnDefinition = "jsonb")
  private String attachments;

  @Column(name = "payload_bytes", nullable = false)
  private byte[] payloadBytes;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  protected InboundEnvelope() {}

  public InboundEnvelope(String containerId, String batchId, String sender, String specVersion,
      String payloadSha256, String signature, boolean signatureValid, String attachments,
      byte[] payloadBytes) {
    this.containerId = containerId;
    this.batchId = batchId;
    this.sender = sender;
    this.specVersion = specVersion;
    this.payloadSha256 = payloadSha256;
    this.signature = signature;
    this.signatureValid = signatureValid;
    this.attachments = attachments;
    this.payloadBytes = payloadBytes;
    this.receivedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public String getContainerId() { return containerId; }
  public String getBatchId() { return batchId; }
  public String getSender() { return sender; }
  public String getSpecVersion() { return specVersion; }
  public String getPayloadSha256() { return payloadSha256; }
  public String getSignature() { return signature; }
  public boolean isSignatureValid() { return signatureValid; }
  public String getAttachments() { return attachments; }
  public byte[] getPayloadBytes() { return payloadBytes; }
  public OffsetDateTime getReceivedAt() { return receivedAt; }
}
