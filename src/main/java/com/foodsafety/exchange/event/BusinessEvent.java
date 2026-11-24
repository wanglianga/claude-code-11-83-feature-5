package com.foodsafety.exchange.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 不可变业务事件。event_seq 为全局单调序列，是重放与投影重建的唯一顺序依据。
 * 该表由数据库触发器禁止 UPDATE/DELETE。
 */
@Entity
@Table(name = "business_event")
public class BusinessEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "event_seq")
  private Long eventSeq;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "container_id")
  private String containerId;

  @Column(name = "sample_key")
  private String sampleKey;

  @Column(name = "report_no")
  private String reportNo;

  @Column(name = "revision_no")
  private Integer revisionNo;

  @Column(name = "operation_no")
  private String operationNo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected BusinessEvent() {}

  public BusinessEvent(EventType type, EventKeys keys, String payloadJson, OffsetDateTime occurredAt) {
    this.eventId = UUID.randomUUID();
    this.eventType = type.name();
    this.containerId = keys.containerId();
    this.sampleKey = keys.sampleKey();
    this.reportNo = keys.reportNo();
    this.revisionNo = keys.revisionNo();
    this.operationNo = keys.operationNo();
    this.payload = payloadJson;
    this.occurredAt = occurredAt;
    this.recordedAt = OffsetDateTime.now();
  }

  public EventType type() {
    return EventType.valueOf(eventType);
  }

  public Long getEventSeq() { return eventSeq; }
  public UUID getEventId() { return eventId; }
  public String getEventType() { return eventType; }
  public String getContainerId() { return containerId; }
  public String getSampleKey() { return sampleKey; }
  public String getReportNo() { return reportNo; }
  public Integer getRevisionNo() { return revisionNo; }
  public String getOperationNo() { return operationNo; }
  public String getPayload() { return payload; }
  public OffsetDateTime getOccurredAt() { return occurredAt; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
