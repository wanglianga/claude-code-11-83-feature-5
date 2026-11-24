package com.foodsafety.exchange.spec;

import java.util.List;

/**
 * 报文解析器。每个规范版本一个实现；容器按其创建时固定的规范版本选择解析器，
 * 校验字段结构与附件引用（单位、检出限等语义校验由业务校验层完成）。
 */
public interface MessageParser {

  SpecVersion version();

  /**
   * @param body     报文正文字节
   * @param manifest 随容器提交的附件声明清单
   * @return 解析出的报告修订条目
   * @throws ParseException 结构校验失败（含附件引用无法解析）
   */
  List<ParsedEntry> parse(byte[] body, List<AttachmentDecl> manifest) throws ParseException;
}
