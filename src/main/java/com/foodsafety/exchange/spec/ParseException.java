package com.foodsafety.exchange.spec;

/** 解析器结构校验失败（对应业务状态 PARSE_FAILED）。 */
public class ParseException extends Exception {

  private final String reasonCode;

  public ParseException(String reasonCode, String message) {
    super(message);
    this.reasonCode = reasonCode;
  }

  public String reasonCode() {
    return reasonCode;
  }
}
