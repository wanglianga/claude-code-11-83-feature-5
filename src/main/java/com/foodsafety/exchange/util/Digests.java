package com.foodsafety.exchange.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 摘要、HMAC 签名与规范化 JSON 工具。 */
public final class Digests {

  private static final ObjectMapper CANONICAL_MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private Digests() {}

  public static String sha256Hex(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return toHex(md.digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256Hex(String text) {
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String hmacSha256Hex(String secret, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(mac.doFinal(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 规范化 JSON（键排序），用于操作载荷的确定性摘要。 */
  public static String canonicalJson(JsonNode node) {
    try {
      Object value = CANONICAL_MAPPER.treeToValue(node, Object.class);
      return CANONICAL_MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("无法规范化 JSON 载荷: " + e.getMessage(), e);
    }
  }

  /** 常量时间比较，避免签名比较时序泄漏。 */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public static String randomId() {
    byte[] buf = new byte[16];
    new SecureRandom().nextBytes(buf);
    return toHex(buf);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
