package com.foodsafety.exchange.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 交换服务配置。
 *
 * @param target   目标端（下游接收系统）连接配置；baseUrl 为空时调用本服务内置模拟目标端
 * @param senders  报文发送方身份 -> HMAC 共享密钥
 * @param security API 测试账号：用户名 -> "密码:角色"
 */
@ConfigurationProperties(prefix = "exchange")
public record ExchangeProperties(Target target, Map<String, String> senders, Security security) {

  public record Target(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {}

  public record Security(Map<String, String> users) {}
}
