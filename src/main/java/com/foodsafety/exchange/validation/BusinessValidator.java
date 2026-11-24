package com.foodsafety.exchange.validation;

import com.foodsafety.exchange.spec.ParsedEntry;
import com.foodsafety.exchange.spec.SpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 业务语义校验：单位词表、检出限单位完整性、结论词表。
 * 校验按容器固定的规范版本执行；未通过时业务状态为 BUSINESS_REJECTED（而非“解析成功”）。
 */
@Component
public class BusinessValidator {

  /** CSV1 单位词表 */
  public static final Set<String> CSV1_UNITS = Set.of("mg/kg", "µg/kg", "mg/L");
  /** XML2 单位词表（新规范扩展） */
  public static final Set<String> XML2_UNITS = Set.of("mg/kg", "µg/kg", "mg/L", "µg/L", "g/kg");

  static final Set<String> CONCLUSIONS = Set.of("PASS", "FAIL");

  public record Issue(String code, String message) {}

  public List<Issue> validate(ParsedEntry entry, SpecVersion version) {
    List<Issue> issues = new ArrayList<>();
    Set<String> units = version == SpecVersion.XML2 ? XML2_UNITS : CSV1_UNITS;

    if (entry.revisionNo() < 1) {
      issues.add(new Issue("INVALID_REVISION", "修订号必须 >= 1: " + entry.revisionNo()));
    }
    if (!units.contains(entry.resultUnit())) {
      issues.add(new Issue("INVALID_RESULT_UNIT",
          "结果单位 " + entry.resultUnit() + " 不在 " + version + " 规范词表内"));
    }
    if (entry.detectionLimit() != null && (entry.detectionLimitUnit() == null || entry.detectionLimitUnit().isBlank())) {
      issues.add(new Issue("MISSING_DETECTION_LIMIT_UNIT",
          "检出限已给出但缺少检出限单位"));
    }
    if (entry.detectionLimitUnit() != null && !units.contains(entry.detectionLimitUnit())) {
      issues.add(new Issue("INVALID_DETECTION_LIMIT_UNIT",
          "检出限单位 " + entry.detectionLimitUnit() + " 不在 " + version + " 规范词表内"));
    }
    if (!CONCLUSIONS.contains(entry.conclusion())) {
      issues.add(new Issue("INVALID_CONCLUSION", "结论 " + entry.conclusion() + " 不在规范词表内"));
    }
    return issues;
  }
}
