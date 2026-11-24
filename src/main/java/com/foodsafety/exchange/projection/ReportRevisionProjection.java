package com.foodsafety.exchange.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 版本化当前投影：按 (报告号, 修订号) 一行，可由事件账重建。 */
@Entity
@Table(name = "report_revision_projection")
@IdClass(ReportRevisionProjection.Key.class)
public class ReportRevisionProjection {

  /** 复合主键。 */
  public static class Key implements Serializable {
    private String reportNo;
    private Integer revisionNo;

    public Key() {}

    public Key(String reportNo, Integer revisionNo) {
      this.reportNo = reportNo;
      this.revisionNo = revisionNo;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Key key)) return false;
      return Objects.equals(reportNo, key.reportNo) && Objects.equals(revisionNo, key.revisionNo);
    }

    @Override
    public int hashCode() {
      return Objects.hash(reportNo, revisionNo);
    }
  }

  @Id
  @Column(name = "report_no")
  private String reportNo;

  @Id
  @Column(name = "revision_no")
  private Integer revisionNo;

  @Column(name = "sample_key", nullable = false)
  private String sampleKey;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "digest", nullable = false)
  private String digest;

  @Column(name = "spec_version", nullable = false)
  private String specVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "supersedes", nullable = false, columnDefinition = "jsonb")
  private String supersedes;

  @Column(name = "container_id")
  private String containerId;

  @Column(name = "late_arrival", nullable = false)
  private boolean lateArrival;

  @Column(name = "reason")
  private String reason;

  @Column(name = "applied_event_seq", nullable = false)
  private long appliedEventSeq;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected ReportRevisionProjection() {}

  public String getReportNo() { return reportNo; }
  public Integer getRevisionNo() { return revisionNo; }
  public String getSampleKey() { return sampleKey; }
  public String getStatus() { return status; }
  public String getDigest() { return digest; }
  public String getSpecVersion() { return specVersion; }
  public String getSupersedes() { return supersedes; }
  public String getContainerId() { return containerId; }
  public boolean isLateArrival() { return lateArrival; }
  public String getReason() { return reason; }
  public long getAppliedEventSeq() { return appliedEventSeq; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  public void apply(String reportNo, Integer revisionNo, String sampleKey, String status,
      String digest, String specVersion, String supersedes, String containerId,
      boolean lateArrival, String reason, long appliedEventSeq, OffsetDateTime updatedAt) {
    this.reportNo = reportNo;
    this.revisionNo = revisionNo;
    this.sampleKey = sampleKey;
    this.status = status;
    this.digest = digest;
    this.specVersion = specVersion;
    this.supersedes = supersedes;
    this.containerId = containerId;
    this.lateArrival = lateArrival;
    this.reason = reason;
    this.appliedEventSeq = appliedEventSeq;
    this.updatedAt = updatedAt;
  }
}
