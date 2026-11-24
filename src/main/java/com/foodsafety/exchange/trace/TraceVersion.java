package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 追溯版本：冻结求值当时使用的生产图、实验结果与候选输出。
 * 该表由数据库触发器禁止 UPDATE/DELETE，供风险沟通与复现，不作法律定责依据。
 */
@Entity
@Table(name = "trace_version")
public class TraceVersion {

  @Id
  @Column(name = "version_id")
  private String versionId;

  @Column(name = "case_id", nullable = false)
  private String caseId;

  @Column(name = "purpose", nullable = false)
  private String purpose;

  @Column(name = "mode", nullable = false)
  private String mode;

  @Column(name = "analyte", nullable = false)
  private String analyte;

  @Column(name = "as_of")
  private OffsetDateTime asOf;

  @Column(name = "graph_sha256", nullable = false)
  private String graphSha256;

  @Column(name = "spectra_sha256", nullable = false)
  private String spectraSha256;

  @Column(name = "inputs_sha256", nullable = false)
  private String inputsSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected TraceVersion() {}

  public TraceVersion(String versionId, String caseId, String purpose, String mode, String analyte,
      OffsetDateTime asOf, String graphSha256, String spectraSha256, String inputsSha256,
      String payloadJson, OffsetDateTime createdAt) {
    this.versionId = versionId;
    this.caseId = caseId;
    this.purpose = purpose;
    this.mode = mode;
    this.analyte = analyte;
    this.asOf = asOf;
    this.graphSha256 = graphSha256;
    this.spectraSha256 = spectraSha256;
    this.inputsSha256 = inputsSha256;
    this.payload = payloadJson;
    this.createdAt = createdAt;
  }

  public String getVersionId() { return versionId; }
  public String getCaseId() { return caseId; }
  public String getPurpose() { return purpose; }
  public String getMode() { return mode; }
  public String getAnalyte() { return analyte; }
  public OffsetDateTime getAsOf() { return asOf; }
  public String getGraphSha256() { return graphSha256; }
  public String getSpectraSha256() { return spectraSha256; }
  public String getInputsSha256() { return inputsSha256; }
  public String getPayload() { return payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
