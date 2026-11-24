package com.foodsafety.exchange.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 污染谱账：化学指纹 + 检出限 + 检测不确定度；polarity 区分阳性/阴性。
 * 指纹相似度只在检测不确定度范围内比较，不能把单次阳性直接写成责任。
 */
@Entity
@Table(name = "trace_spectrum")
public class TraceSpectrum {

  @Id
  @Column(name = "spectrum_id")
  private String spectrumId;

  @Column(name = "sample_key", nullable = false)
  private String sampleKey;

  @Column(name = "analyte", nullable = false)
  private String analyte;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fingerprint", nullable = false, columnDefinition = "jsonb")
  private String fingerprint;

  @Column(name = "detection_limit")
  private BigDecimal detectionLimit;

  @Column(name = "detection_limit_unit")
  private String detectionLimitUnit;

  @Column(name = "relative_uncertainty")
  private BigDecimal relativeUncertainty;

  @Column(name = "result_value")
  private BigDecimal resultValue;

  @Column(name = "polarity", nullable = false)
  private String polarity;

  @Column(name = "lab")
  private String lab;

  @Column(name = "measured_at", nullable = false)
  private OffsetDateTime measuredAt;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TraceSpectrum() {}

  public TraceSpectrum(String spectrumId, String sampleKey, String analyte, String fingerprintJson,
      BigDecimal detectionLimit, String detectionLimitUnit, BigDecimal relativeUncertainty,
      BigDecimal resultValue, String polarity, String lab, OffsetDateTime measuredAt) {
    this.spectrumId = spectrumId;
    this.sampleKey = sampleKey;
    this.analyte = analyte;
    this.fingerprint = fingerprintJson == null ? "{}" : fingerprintJson;
    this.detectionLimit = detectionLimit;
    this.detectionLimitUnit = detectionLimitUnit;
    this.relativeUncertainty = relativeUncertainty;
    this.resultValue = resultValue;
    this.polarity = polarity;
    this.lab = lab;
    this.measuredAt = measuredAt;
    this.recordedAt = OffsetDateTime.now();
  }

  public String getSpectrumId() { return spectrumId; }
  public String getSampleKey() { return sampleKey; }
  public String getAnalyte() { return analyte; }
  public String getFingerprint() { return fingerprint; }
  public BigDecimal getDetectionLimit() { return detectionLimit; }
  public String getDetectionLimitUnit() { return detectionLimitUnit; }
  public BigDecimal getRelativeUncertainty() { return relativeUncertainty; }
  public BigDecimal getResultValue() { return resultValue; }
  public String getPolarity() { return polarity; }
  public String getLab() { return lab; }
  public OffsetDateTime getMeasuredAt() { return measuredAt; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
