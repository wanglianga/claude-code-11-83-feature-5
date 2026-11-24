package com.foodsafety.exchange.spec;

/** 报文规范版本。容器启动（创建）时由发送批次固定，解析器按该版本校验。 */
public enum SpecVersion {
  /** 第一版：CSV 正文 */
  CSV1,
  /** 第二版：XML 正文，新批次起生效 */
  XML2;

  public static SpecVersion of(String value) {
    try {
      return SpecVersion.valueOf(value);
    } catch (Exception e) {
      return null;
    }
  }
}
