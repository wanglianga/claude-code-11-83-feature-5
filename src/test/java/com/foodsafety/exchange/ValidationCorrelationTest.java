package com.foodsafety.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** 解析/业务校验与修订关联状态机的边界用例。 */
class ValidationCorrelationTest extends ExchangeIntegrationTest {

  @Test
  void validSignatureButMissingDetectionLimitUnit_isBusinessRejectedNotParseSuccess() {
    JsonNode result = ingest("C4", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S18", "R9", 1, null, "0.05", "mg/kg", "0.01", null, "PASS")));
    assertThat(result.path("signatureValid").asBoolean()).isTrue();
    assertThat(result.path("containerStatus").asText()).isEqualTo("RECEIVED");
    JsonNode entry = result.path("entries").get(0);
    assertThat(entry.path("status").asText()).isEqualTo("BUSINESS_REJECTED");
    assertThat(entry.path("reason").asText()).isEqualTo("MISSING_DETECTION_LIMIT_UNIT");
  }

  @Test
  void invalidSignature_isParseFailed() {
    byte[] body = csv(csvRow("S1", "R1", 1, null, "0.02", "mg/kg", null, null, "PASS"));
    JsonNode result = ingestWithBadSignature("C9", body);
    assertThat(result.path("signatureValid").asBoolean()).isFalse();
    assertThat(result.path("containerStatus").asText()).isEqualTo("PARSE_FAILED");
    assertThat(result.path("entries").size()).isZero();
  }

  @Test
  void unknownAttachmentReference_isParseFailed() {
    String xml = xmlReport("S21", "R10", 1, null, "0.03", "mg/kg", null, null)
        .replace("</report>", "<attachments><ref>att-x</ref></attachments></report>");
    JsonNode result = ingest("C10", "B2026-11", "lab-alpha", LAB_SECRET,
        xml.getBytes(), "[{\"id\":\"att-9\",\"sha256\":\"x\"}]");
    assertThat(result.path("containerStatus").asText()).isEqualTo("PARSE_FAILED");
    String reason = jdbc.queryForObject(
        "SELECT payload->>'reason' FROM business_event WHERE event_type='PARSE_FAILED' "
            + "ORDER BY event_seq DESC LIMIT 1", String.class);
    assertThat(reason).isEqualTo("UNKNOWN_ATTACHMENT_REF");
  }

  @Test
  void unitVocabularyFollowsPinnedSpecVersion() {
    // µg/L 不在 CSV1 词表 -> 业务拒绝
    JsonNode csv1 = ingest("CU1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S30", "R30", 1, null, "0.02", "µg/L", null, null, "PASS")));
    assertThat(csv1.path("entries").get(0).path("reason").asText())
        .isEqualTo("INVALID_RESULT_UNIT");

    // 同样的 µg/L 在 XML2 词表内 -> 受理
    String xml = xmlReport("S31", "R31", 1, null, "0.02", "µg/L", null, null);
    JsonNode xml2 = ingest("CU2", "B2026-11", "lab-alpha", LAB_SECRET, xml.getBytes());
    assertThat(xml2.path("entries").get(0).path("status").asText()).isEqualTo("ACCEPTED");
  }

  @Test
  void parallelConclusionWithoutSupersedes_isQuarantined() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    ingest("C2", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 2, "1", "0.019", "mg/kg", null, null, "PASS")));
    // 修订4 无 supersedes -> 隔离
    JsonNode c5 = ingest("C5", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 4, null, "0.017", "mg/kg", null, null, "PASS")));
    assertThat(c5.path("entries").get(0).path("status").asText()).isEqualTo("QUARANTINED");
    assertThat(getJson("/api/projections/reports/R7").path("head").asInt()).isEqualTo(2);
  }

  @Test
  void olderRevisionArrivingLate_doesNotOverwriteNewerHead() {
    // R8 先到修订2（声明取代1），再到晚到的修订1
    JsonNode first = ingest("C6", "B2026-01", "lab-beta", "lab-beta-secret",
        csv(csvRow("S20", "R8", 2, "1", "0.01", "mg/kg", null, null, "FAIL")));
    assertThat(first.path("entries").get(0).path("status").asText()).isEqualTo("ACCEPTED");

    JsonNode late = ingest("C7", "B2026-01", "lab-beta", "lab-beta-secret",
        csv(csvRow("S20", "R8", 1, null, "0.011", "mg/kg", null, null, "FAIL")));
    assertThat(late.path("entries").get(0).path("status").asText())
        .isEqualTo("LATE_REVISION_IGNORED");
    JsonNode proj = getJson("/api/projections/reports/R8");
    assertThat(proj.path("head").asInt()).isEqualTo(2);
    JsonNode rev1 = proj.path("revisions").get(0);
    assertThat(rev1.path("lateArrival").asBoolean()).isTrue();
  }

  @Test
  void sameRevisionNumberWithDifferentDigest_isRevisionConflict() {
    ingest("C1", "B2026-11", "lab-alpha", LAB_SECRET,
        xmlReport("S17", "R7", 3, "2", "0.018", "mg/kg", null, null).getBytes());
    // 同修订号 3，结果值不同 -> 摘要不同 -> 冲突
    JsonNode conflict = ingest("C8", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 3, "2", "0.099", "mg/kg", null, null, "PASS")));
    assertThat(conflict.path("entries").get(0).path("status").asText())
        .isEqualTo("REVISION_CONFLICT");
  }

  @Test
  void sameContainerIdWithDifferentPayload_isContainerConflict409() {
    byte[] body1 = csv(csvRow("S1", "R1", 1, null, "0.02", "mg/kg", null, null, "PASS"));
    ingest("CX", "B2026-01", "lab-alpha", LAB_SECRET, body1);
    byte[] body2 = csv(csvRow("S1", "R1", 1, null, "0.03", "mg/kg", null, null, "PASS"));
    // 直接用 TestRestTemplate 拿状态码
    org.springframework.http.ResponseEntity<JsonNode> response = authed().exchange(
        "/api/inbound/containers",
        org.springframework.http.HttpMethod.POST,
        multipartEntity("CX", "B2026-01", "lab-alpha", hmac(LAB_SECRET, body2), body2),
        JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().path("conflict").asBoolean()).isTrue();
  }

  @Test
  void unknownBatchOrSender_isRejected() {
    byte[] body = csv(csvRow("S1", "R1", 1, null, "0.02", "mg/kg", null, null, "PASS"));
    org.springframework.http.ResponseEntity<JsonNode> r1 = authed().exchange(
        "/api/inbound/containers", org.springframework.http.HttpMethod.POST,
        multipartEntity("C1", "B-NOPE", "lab-alpha", hmac(LAB_SECRET, body), body),
        JsonNode.class);
    assertThat(r1.getStatusCode().value()).isEqualTo(400);

    org.springframework.http.ResponseEntity<JsonNode> r2 = authed().exchange(
        "/api/inbound/containers", org.springframework.http.HttpMethod.POST,
        multipartEntity("C2", "B2026-01", "nobody", "x", body), JsonNode.class);
    assertThat(r2.getStatusCode().value()).isEqualTo(400);
  }

  private org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>>
      multipartEntity(String containerId, String batchId, String sender, String signature, byte[] body) {
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
    org.springframework.util.MultiValueMap<String, Object> form =
        new org.springframework.util.LinkedMultiValueMap<>();
    form.add("containerId", containerId);
    form.add("batchId", batchId);
    form.add("sender", sender);
    form.add("signature", signature);
    form.add("file", new org.springframework.core.io.ByteArrayResource(body) {
      @Override
      public String getFilename() {
        return "c.csv";
      }
    });
    return new org.springframework.http.HttpEntity<>(form, headers);
  }
}
