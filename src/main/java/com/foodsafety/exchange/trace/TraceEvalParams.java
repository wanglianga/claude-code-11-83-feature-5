package com.foodsafety.exchange.trace;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 求值参数。
 *
 * @param analyte        目标污染物
 * @param mode           CONSTRAINT（硬约束交集）或 PROBABILISTIC（概率来源模型）
 * @param tolerance      指纹相容容差（0..0.5）
 * @param backgroundRate 背景污染率（概率模式使用；空则用默认值并在输出中标注假设）
 * @param asOf           结果时点：只用该时点前测得的实验结果
 * @param onlySampleKeys 可选：仅对这些样品求值（便于回归/对比）
 * @param useFingerprint 是否把指纹相容度作为硬约束（关闭后仅按时间可达求值，用于对比指纹证据的增减效应）
 */
public record TraceEvalParams(
    String analyte,
    String mode,
    double tolerance,
    Double backgroundRate,
    OffsetDateTime asOf,
    List<String> onlySampleKeys,
    boolean useFingerprint) {

  public static final String CONSTRAINT = "CONSTRAINT";
  public static final String PROBABILISTIC = "PROBABILISTIC";

  public TraceEvalParams {
    if (analyte == null || analyte.isBlank()) {
      throw new IllegalArgumentException("analyte 不能为空");
    }
    if (mode == null || mode.isBlank()) {
      mode = CONSTRAINT;
    }
    mode = mode.toUpperCase();
    if (!mode.equals(CONSTRAINT) && !mode.equals(PROBABILISTIC)) {
      throw new IllegalArgumentException("mode 必须是 CONSTRAINT 或 PROBABILISTIC: " + mode);
    }
    if (tolerance < 0 || tolerance > 0.5) {
      throw new IllegalArgumentException("tolerance 必须在 [0, 0.5]");
    }
    if (backgroundRate != null && (backgroundRate <= 0 || backgroundRate >= 1)) {
      throw new IllegalArgumentException("backgroundRate 必须在 (0,1)");
    }
  }

  public boolean isProbabilistic() {
    return PROBABILISTIC.equals(mode);
  }
}
