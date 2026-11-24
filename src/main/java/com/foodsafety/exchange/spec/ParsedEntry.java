package com.foodsafety.exchange.spec;

import com.foodsafety.exchange.util.Digests;
import java.math.BigDecimal;
import java.util.List;
import java.util.TreeMap;

/**
 * 一条解析出的报告修订条目。digest 为条目内容的确定性摘要，
 * 用于“同报告修订且相同摘要”的重复到达判定。
 */
public record ParsedEntry(
    String sampleKey,
    String reportNo,
    int revisionNo,
    List<Integer> supersedes,
    String analyte,
    BigDecimal resultValue,
    String resultUnit,
    BigDecimal detectionLimit,
    String detectionLimitUnit,
    String conclusion,
    List<String> attachmentRefs,
    String digest) {

  public static ParsedEntry of(
      String sampleKey,
      String reportNo,
      int revisionNo,
      List<Integer> supersedes,
      String analyte,
      BigDecimal resultValue,
      String resultUnit,
      BigDecimal detectionLimit,
      String detectionLimitUnit,
      String conclusion,
      List<String> attachmentRefs) {
    TreeMap<String, Object> canonical = new TreeMap<>();
    canonical.put("sampleKey", sampleKey);
    canonical.put("reportNo", reportNo);
    canonical.put("revisionNo", revisionNo);
    canonical.put("supersedes", supersedes.stream().sorted().toList());
    canonical.put("analyte", analyte);
    canonical.put("resultValue", resultValue.stripTrailingZeros().toPlainString());
    canonical.put("resultUnit", resultUnit);
    canonical.put("detectionLimit",
        detectionLimit == null ? null : detectionLimit.stripTrailingZeros().toPlainString());
    canonical.put("detectionLimitUnit", detectionLimitUnit);
    canonical.put("conclusion", conclusion);
    canonical.put("attachmentRefs", attachmentRefs.stream().sorted().toList());
    String digest = Digests.sha256Hex(canonical.toString());
    return new ParsedEntry(sampleKey, reportNo, revisionNo, List.copyOf(supersedes), analyte,
        resultValue, resultUnit, detectionLimit, detectionLimitUnit, conclusion,
        List.copyOf(attachmentRefs), digest);
  }
}
