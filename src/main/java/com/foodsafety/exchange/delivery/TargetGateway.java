package com.foodsafety.exchange.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.foodsafety.exchange.config.ExchangeProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 目标端网关：负责向目标系统投递与按原操作号查询目标状态。
 * 超时/连接错误统一映射为 {@link TargetUnavailableException}（结果未知）。
 */
@Component
public class TargetGateway {

  /** 目标端投递请求。 */
  public record TargetDeliveryRequest(String operationNo, String reportNo, int revisionNo,
      String deliveryType, JsonNode payload, String payloadSha256) {}

  /** 目标端回执视图。 */
  public record TargetReceiptView(String receiptId, String receiptType, String issuedAt) {}

  /** 目标端投递响应。 */
  public record TargetDeliveryResponse(String status, TargetReceiptView receipt) {}

  /** 目标端状态查询视图。 */
  public record TargetStatusView(String operationNo, String status, List<TargetReceiptView> receipts) {}

  private final ExchangeProperties properties;
  private final ObjectProvider<WebServerApplicationContext> webServerContext;
  private volatile RestClient client;

  public TargetGateway(ExchangeProperties properties,
      ObjectProvider<WebServerApplicationContext> webServerContext) {
    this.properties = properties;
    this.webServerContext = webServerContext;
  }

  /** 目标端标识（用于操作账记录）。 */
  public String targetEndpoint() {
    return resolveBaseUrl() + "/target/deliveries";
  }

  public TargetDeliveryResponse deliver(TargetDeliveryRequest request) {
    try {
      return client().post()
          .uri("/target/deliveries")
          .body(request)
          .retrieve()
          .body(TargetDeliveryResponse.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 409) {
        throw new TargetConflictException("目标端拒绝：操作号冲突 " + request.operationNo());
      }
      throw new TargetUnavailableException("目标端响应异常: " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      // 含超时（读超时发生在响应提取阶段，被包装为 RestClientException）与连接错误
      throw new TargetUnavailableException("目标端超时或不可达: " + rootMessage(e), e);
    }
  }

  /** 按原操作号查询目标端状态；404 返回 empty（目标端未收到）。 */
  public Optional<TargetStatusView> query(String operationNo) {
    try {
      TargetStatusView view = client().get()
          .uri("/target/deliveries/{operationNo}", operationNo)
          .retrieve()
          .body(TargetStatusView.class);
      return Optional.ofNullable(view);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Optional.empty();
      }
      throw new TargetUnavailableException("目标端查询异常: " + e.getStatusCode(), e);
    } catch (RestClientException e) {
      throw new TargetUnavailableException("目标端查询超时或不可达: " + rootMessage(e), e);
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }

  private RestClient client() {
    if (client == null) {
      synchronized (this) {
        if (client == null) {
          SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
          factory.setConnectTimeout(properties.target().connectTimeoutMs());
          factory.setReadTimeout(properties.target().readTimeoutMs());
          client = RestClient.builder()
              .requestFactory(factory)
              .baseUrl(resolveBaseUrl())
              .build();
        }
      }
    }
    return client;
  }

  private String resolveBaseUrl() {
    String configured = properties.target().baseUrl();
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    // 未配置时指向本服务内置的模拟目标端
    WebServerApplicationContext context = webServerContext.getIfAvailable();
    int port = context != null ? context.getWebServer().getPort() : 8080;
    return "http://localhost:" + port;
  }
}
