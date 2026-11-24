package com.foodsafety.exchange.spec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * CSV1 规范解析器（B2026-01 批次起固定）。
 * 结构校验：表头、列数、必填字段、数值格式、附件引用。
 */
@Component
public class CsvV1Parser implements MessageParser {

  static final String[] HEADER = {
    "sample_key", "report_no", "revision_no", "supersedes", "analyte",
    "result_value", "result_unit", "detection_limit", "detection_limit_unit",
    "conclusion", "attachment_refs"
  };

  @Override
  public SpecVersion version() {
    return SpecVersion.CSV1;
  }

  @Override
  public List<ParsedEntry> parse(byte[] body, List<AttachmentDecl> manifest) throws ParseException {
    String text = new String(body, StandardCharsets.UTF_8);
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
    if (lines.isEmpty()) {
      throw new ParseException("EMPTY_BODY", "CSV 正文为空");
    }
    List<String> header = parseLine(lines.get(0));
    if (!header.equals(Arrays.asList(HEADER))) {
      throw new ParseException("BAD_HEADER",
          "CSV 表头不符合 CSV1 规范，期望: " + String.join(",", HEADER));
    }
    Set<String> manifestIds = manifest.stream().map(AttachmentDecl::id).collect(Collectors.toSet());
    List<ParsedEntry> entries = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      entries.add(parseRow(lines.get(i), i + 1, manifestIds));
    }
    if (entries.isEmpty()) {
      throw new ParseException("NO_ENTRIES", "CSV 无数据行");
    }
    return entries;
  }

  private ParsedEntry parseRow(String line, int lineNo, Set<String> manifestIds) throws ParseException {
    List<String> cols = parseLine(line);
    if (cols.size() != HEADER.length) {
      throw new ParseException("BAD_COLUMN_COUNT",
          "第 " + lineNo + " 行列数 " + cols.size() + " 不符合 CSV1 规范（应为 " + HEADER.length + "）");
    }
    String sampleKey = required(cols.get(0), "sample_key", lineNo);
    String reportNo = required(cols.get(1), "report_no", lineNo);
    int revisionNo = parseInt(required(cols.get(2), "revision_no", lineNo), "revision_no", lineNo);
    List<Integer> supersedes = parseIntList(cols.get(3), "supersedes", lineNo);
    String analyte = required(cols.get(4), "analyte", lineNo);
    BigDecimal resultValue = parseDecimal(required(cols.get(5), "result_value", lineNo), "result_value", lineNo);
    String resultUnit = required(cols.get(6), "result_unit", lineNo);
    BigDecimal detectionLimit = cols.get(7).isBlank()
        ? null : parseDecimal(cols.get(7), "detection_limit", lineNo);
    String detectionLimitUnit = cols.get(8).isBlank() ? null : cols.get(8).trim();
    String conclusion = required(cols.get(9), "conclusion", lineNo);
    List<String> attachmentRefs = parseRefList(cols.get(10));
    for (String ref : attachmentRefs) {
      if (!manifestIds.contains(ref)) {
        throw new ParseException("UNKNOWN_ATTACHMENT_REF",
            "第 " + lineNo + " 行引用的附件 " + ref + " 不在容器附件清单中");
      }
    }
    return ParsedEntry.of(sampleKey, reportNo, revisionNo, supersedes, analyte,
        resultValue, resultUnit, detectionLimit, detectionLimitUnit, conclusion, attachmentRefs);
  }

  private String required(String value, String field, int lineNo) throws ParseException {
    if (value == null || value.isBlank()) {
      throw new ParseException("MISSING_FIELD", "第 " + lineNo + " 行缺少必填字段 " + field);
    }
    return value.trim();
  }

  private int parseInt(String value, String field, int lineNo) throws ParseException {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new ParseException("BAD_NUMBER", "第 " + lineNo + " 行字段 " + field + " 不是整数: " + value);
    }
  }

  private BigDecimal parseDecimal(String value, String field, int lineNo) throws ParseException {
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      throw new ParseException("BAD_NUMBER", "第 " + lineNo + " 行字段 " + field + " 不是数值: " + value);
    }
  }

  private List<Integer> parseIntList(String value, String field, int lineNo) throws ParseException {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<Integer> result = new ArrayList<>();
    for (String part : value.split(";")) {
      result.add(parseInt(part, field, lineNo));
    }
    return result;
  }

  private List<String> parseRefList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /** 最小 RFC4180 行解析：支持双引号包裹与 "" 转义。 */
  static List<String> parseLine(String line) {
    List<String> cols = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        inQuotes = true;
      } else if (c == ',') {
        cols.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    cols.add(cur.toString());
    return cols;
  }
}
