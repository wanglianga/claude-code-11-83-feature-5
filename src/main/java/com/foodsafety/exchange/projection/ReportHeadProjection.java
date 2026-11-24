package com.foodsafety.exchange.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 报告号分区的链头投影：分区版本 = 该分区已应用事件数。 */
@Entity
@Table(name = "report_head_projection")
public class ReportHeadProjection {

  @Id
  @Column(name = "report_no")
  private String reportNo;

  @Column(name = "sample_key", nullable = false)
  private String sampleKey;

  @Column(name = "head_revision")
  private Integer headRevision;

  @Column(name = "head_status")
  private String headStatus;

  @Column(name = "partition_version", nullable = false)
  private long partitionVersion;

  @Column(name = "last_event_seq", nullable = false)
  private long lastEventSeq;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected ReportHeadProjection() {}

  public ReportHeadProjection(String reportNo) {
    this.reportNo = reportNo;
  }

  public String getReportNo() { return reportNo; }
  public String getSampleKey() { return sampleKey; }
  public Integer getHeadRevision() { return headRevision; }
  public String getHeadStatus() { return headStatus; }
  public long getPartitionVersion() { return partitionVersion; }
  public long getLastEventSeq() { return lastEventSeq; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  public void update(String sampleKey, Integer headRevision, String headStatus,
      long partitionVersion, long lastEventSeq, OffsetDateTime updatedAt) {
    this.sampleKey = sampleKey;
    this.headRevision = headRevision;
    this.headStatus = headStatus;
    this.partitionVersion = partitionVersion;
    this.lastEventSeq = lastEventSeq;
    this.updatedAt = updatedAt;
  }
}
