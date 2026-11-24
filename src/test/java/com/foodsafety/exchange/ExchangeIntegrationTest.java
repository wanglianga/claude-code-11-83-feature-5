package com.foodsafety.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 集成测试基类：真实 PostgreSQL，每例清空业务表并复位故障开关。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ExchangeIntegrationTest {

  protected static final String ADMIN_USER = "admin";
  protected static final String ADMIN_PASS = "admin123";
  protected static final String LAB_SECRET = "lab-alpha-secret";

  @Autowired
  protected TestRestTemplate rest;

  @Autowired
  protected JdbcTemplate jdbc;

  @org.junit.jupiter.api.BeforeEach
  void resetDatabase() {
    jdbc.execute("TRUNCATE inbound_envelope, business_event, report_revision_projection, "
        + "report_head_projection, operation_ledger, receipt, target_inbox, target_receipt "
        + "RESTART IDENTITY CASCADE");
    jdbc.update("UPDATE fault_switch SET timeout_next = FALSE WHERE id = 1");
  }

  protected TestRestTemplate authed() {
    return rest.withBasicAuth(ADMIN_USER, ADMIN_PASS);
  }

  // ---------- 报文构造 ----------

  protected String csvRow(String sample, String report, int rev, String supersedes,
      String resultValue, String resultUnit, String detectionLimit, String dlUnit,
      String conclusion) {
    return String.join(",", sample, report, String.valueOf(rev),
        supersedes == null ? "" : supersedes, "铅", resultValue, resultUnit,
        detectionLimit == null ? "" : detectionLimit, dlUnit == null ? "" : dlUnit,
        conclusion, "");
  }

  protected byte[] csv(String... rows) {
    String header = "sample_key,report_no,revision_no,supersedes,analyte,result_value,"
        + "result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs\n";
    return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  protected String xmlReport(String sample, String report, int rev, String supersedesCsv,
      String value, String unit, String dlValue, String dlUnit) {
    StringBuilder sup = new StringBuilder();
    if (supersedesCsv != null && !supersedesCsv.isBlank()) {
      sup.append("<supersedes>");
      for (String r : supersedesCsv.split(";")) {
        sup.append("<rev>").append(r).append("</rev>");
      }
      sup.append("</supersedes>");
    }
    String dl = dlValue == null ? ""
        : "<detectionLimit><value>" + dlValue + "</value>"
            + (dlUnit == null ? "" : "<unit>" + dlUnit + "</unit>") + "</detectionLimit>";
    return "<reportBatch><report>"
        + "<sampleKey>" + sample + "</sampleKey>"
        + "<reportNo>" + report + "</reportNo>"
        + "<revision>" + rev + "</revision>"
        + sup
        + "<analyte>铅</analyte>"
        + "<result><value>" + value + "</value><unit>" + unit + "</unit></result>"
        + dl
        + "<conclusion>PASS</conclusion>"
        + "</report></reportBatch>";
  }

  protected String hmac(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- API 调用 ----------

  protected JsonNode ingest(String containerId, String batchId, String sender, String secret,
      byte[] body) {
    return ingest(containerId, batchId, sender, secret, body, null, true);
  }

  protected JsonNode ingest(String containerId, String batchId, String sender, String secret,
      byte[] body, String attachmentsJson) {
    return ingest(containerId, batchId, sender, secret, body, attachmentsJson, true);
  }

  protected JsonNode ingestWithBadSignature(String containerId, byte[] body) {
    return ingest(containerId, "B2026-01", "lab-alpha", "wrong-secret", body, null, false);
  }

  private JsonNode ingest(String containerId, String batchId, String sender, String secret,
      byte[] body, String attachmentsJson, boolean signCorrect) {
    String signature = signCorrect ? hmac(secret, body) : "deadbeef";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("containerId", containerId);
    form.add("batchId", batchId);
    form.add("sender", sender);
    form.add("signature", signature);
    if (attachmentsJson != null) {
      form.add("attachments", attachmentsJson);
    }
    final byte[] bytes = body;
    form.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return "container.bin";
      }
    });
    ResponseEntity<JsonNode> response = authed().exchange(
        "/api/inbound/containers", HttpMethod.POST,
        new HttpEntity<>(form, headers), JsonNode.class);
    return response.getBody();
  }

  protected ResponseEntity<JsonNode> deliverRaw(String operationNo, String reportNo, int rev,
      String deliveryType, String payloadJson) {
    String json = "{\"operationNo\":\"" + operationNo + "\",\"reportNo\":\"" + reportNo
        + "\",\"revisionNo\":" + rev + ",\"deliveryType\":\"" + deliveryType
        + "\",\"payload\":" + payloadJson + "}";
    return authed().postForEntity("/api/deliveries",
        new HttpEntity<>(json, jsonHeaders()), JsonNode.class);
  }

  protected JsonNode deliver(String operationNo, String reportNo, int rev, String payloadJson) {
    return deliverRaw(operationNo, reportNo, rev, "REPORT", payloadJson).getBody();
  }

  protected JsonNode retry(String operationNo) {
    return authed().postForObject("/api/deliveries/" + operationNo + "/retry",
        HttpEntity.EMPTY, JsonNode.class);
  }

  protected JsonNode getJson(String path) {
    return authed().getForObject(path, JsonNode.class);
  }

  protected JsonNode postJson(String path, String json) {
    return authed().postForObject(path, new HttpEntity<>(json, jsonHeaders()), JsonNode.class);
  }

  protected void armTimeoutFault() {
    postJson("/target/faults", "{\"timeoutNext\":true}");
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  protected Map<String, Object> payload(Object... kv) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put((String) kv[i], kv[i + 1]);
    }
    return map;
  }
}
