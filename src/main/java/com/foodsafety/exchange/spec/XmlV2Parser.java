package com.foodsafety.exchange.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * XML2 规范解析器（B2026-11 批次起生效）。
 * 结构校验：必填元素、数值格式、附件引用；单位与检出限完整性由业务校验层判定。
 */
@Component
public class XmlV2Parser implements MessageParser {

  private final XmlMapper xmlMapper;

  public XmlV2Parser() {
    this.xmlMapper = new XmlMapper();
    // DTO 使用包私有字段，需放开字段可见性
    this.xmlMapper.setVisibility(
        com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
  }

  @Override
  public SpecVersion version() {
    return SpecVersion.XML2;
  }

  @Override
  public List<ParsedEntry> parse(byte[] body, List<AttachmentDecl> manifest) throws ParseException {
    XmlReportBatch batch;
    try {
      batch = xmlMapper.readValue(body, XmlReportBatch.class);
    } catch (Exception e) {
      throw new ParseException("BAD_XML", "XML 正文不符合 XML2 规范: " + e.getMessage());
    }
    if (batch == null || batch.reports == null || batch.reports.isEmpty()) {
      throw new ParseException("NO_ENTRIES", "XML 中无 report 元素");
    }
    Set<String> manifestIds = manifest.stream().map(AttachmentDecl::id).collect(Collectors.toSet());
    List<ParsedEntry> entries = new ArrayList<>();
    int index = 0;
    for (XmlReport report : batch.reports) {
      index++;
      entries.add(toEntry(report, index, manifestIds));
    }
    return entries;
  }

  private ParsedEntry toEntry(XmlReport report, int index, Set<String> manifestIds) throws ParseException {
    String sampleKey = required(report.sampleKey, "sampleKey", index);
    String reportNo = required(report.reportNo, "reportNo", index);
    if (report.revision == null) {
      throw new ParseException("MISSING_FIELD", "第 " + index + " 个 report 缺少 revision");
    }
    String analyte = required(report.analyte, "analyte", index);
    if (report.result == null || report.result.value == null) {
      throw new ParseException("MISSING_FIELD", "第 " + index + " 个 report 缺少 result/value");
    }
    String resultUnit = required(report.result.unit, "result/unit", index);
    String conclusion = required(report.conclusion, "conclusion", index);
    List<Integer> supersedes = report.supersedes == null || report.supersedes.rev == null
        ? List.of() : List.copyOf(report.supersedes.rev);
    BigDecimal detectionLimit = report.detectionLimit == null ? null : report.detectionLimit.value;
    String detectionLimitUnit = report.detectionLimit == null
        || report.detectionLimit.unit == null
        || report.detectionLimit.unit.isBlank()
        ? null : report.detectionLimit.unit.trim();
    List<String> refs = report.attachments == null || report.attachments.ref == null
        ? List.of() : List.copyOf(report.attachments.ref);
    for (String ref : refs) {
      if (!manifestIds.contains(ref)) {
        throw new ParseException("UNKNOWN_ATTACHMENT_REF",
            "第 " + index + " 个 report 引用的附件 " + ref + " 不在容器附件清单中");
      }
    }
    return ParsedEntry.of(sampleKey, reportNo, report.revision, supersedes, analyte,
        report.result.value, resultUnit, detectionLimit, detectionLimitUnit, conclusion, refs);
  }

  private String required(String value, String field, int index) throws ParseException {
    if (value == null || value.isBlank()) {
      throw new ParseException("MISSING_FIELD", "第 " + index + " 个 report 缺少必填字段 " + field);
    }
    return value.trim();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JacksonXmlRootElement(localName = "reportBatch")
  static class XmlReportBatch {
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "report")
    List<XmlReport> reports;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class XmlReport {
    String sampleKey;
    String reportNo;
    Integer revision;
    XmlSupersedes supersedes;
    String analyte;
    XmlAmount result;
    XmlAmount detectionLimit;
    String conclusion;
    XmlAttachments attachments;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class XmlSupersedes {
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "rev")
    List<Integer> rev;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class XmlAmount {
    BigDecimal value;
    String unit;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class XmlAttachments {
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "ref")
    List<String> ref;
  }
}
