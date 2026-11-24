package com.foodsafety.exchange.trace;

import java.util.Map;

/** 化学指纹工具：余弦相似度与相容阈值。指纹为空表示未知，不能据此剪枝。 */
public final class Fingerprints {

  private Fingerprints() {}

  /**
   * 余弦相似度。任一指纹为空返回 null（未知）；向量范数为 0 返回 0。结果裁剪到 [0,1]。
   */
  public static Double cosine(Map<String, Double> a, Map<String, Double> b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return null;
    }
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (Map.Entry<String, Double> e : a.entrySet()) {
      double v = e.getValue() == null ? 0 : e.getValue();
      na += v * v;
      Double other = b.get(e.getKey());
      if (other != null) {
        dot += v * other;
      }
    }
    for (double v : b.values()) {
      nb += v * v;
    }
    if (na <= 0 || nb <= 0) {
      return 0.0;
    }
    double sim = dot / (Math.sqrt(na) * Math.sqrt(nb));
    return Math.max(0.0, Math.min(1.0, sim));
  }

  /**
   * 相容阈值 = 1 - （请求容差 + 检测不确定度），并裁剪到 [0.40, 0.95]。
   * 检测不确定度越大，越难据此排除批次。
   */
  public static double compatibilityThreshold(double requestedTolerance, Double relativeUncertainty) {
    double uncertainty = relativeUncertainty == null ? 0.0 : relativeUncertainty;
    double tolerance = requestedTolerance + uncertainty;
    tolerance = Math.max(0.05, Math.min(0.60, tolerance));
    return Math.max(0.40, Math.min(0.95, 1.0 - tolerance));
  }
}
