package com.foodsafety.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 操作账幂等、未知查询协调、重复到达与回执类型测试。 */
class DeliveryIdempotencyTest extends ExchangeIntegrationTest {

  private void seedAcceptedRevision() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
  }

  @Test
  void sameOperationAndPayload_returnsOriginalResultOnly() {
    seedAcceptedRevision();
    JsonNode first = deliver("O1", "R7", 1, "{\"a\":1}");
    assertThat(first.path("status").asText()).isEqualTo("ACCEPTED");
    JsonNode second = deliver("O1", "R7", 1, "{\"a\":1}");
    assertThat(second.path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(second.path("replayed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_ledger", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM target_inbox", Integer.class)).isEqualTo(1);
  }

  @Test
  void sameOperationDifferentPayload_conflictAndOriginalUntouched() {
    seedAcceptedRevision();
    deliver("O1", "R7", 1, "{\"a\":1}");
    ResponseEntity<JsonNode> conflict =
        deliverRaw("O1", "R7", 1, "REPORT", "{\"a\":2}");
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().path("status").asText()).isEqualTo("CONFLICT");
    // 原操作仍为 ACCEPTED
    assertThat(getJson("/api/deliveries/O1").path("status").asText()).isEqualTo("ACCEPTED");
    // 冲突事件在账
    Integer conflicts = jdbc.queryForObject(
        "SELECT count(*) FROM business_event WHERE operation_no='O1' "
            + "AND event_type='DELIVERY_CONFLICT'", Integer.class);
    assertThat(conflicts).isEqualTo(1);
  }

  @Test
  void differentOperationSameRevisionAndDigest_recordsDuplicateWithoutReapplying() {
    seedAcceptedRevision();
    deliver("O1", "R7", 1, "{\"a\":1}");
    JsonNode dup = deliver("O9", "R7", 1, "{\"a\":1}");
    assertThat(dup.path("status").asText()).isEqualTo("DUPLICATE");
    assertThat(dup.path("duplicateOf").asText()).isEqualTo("O1");
    // 目标端只有 O1 一次写入，O9 未投递
    assertThat(jdbc.queryForObject("SELECT count(*) FROM target_inbox", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM target_inbox WHERE operation_no='O9'", Integer.class)).isZero();
  }

  @Test
  void unknownThenQueryFindsAccepted_convergesWithoutNewOperation() {
    seedAcceptedRevision();
    armTimeoutFault();
    JsonNode unknown = deliver("O2", "R7", 1, "{\"a\":2}");
    assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");

    JsonNode resolved = retry("O2");
    assertThat(resolved.path("status").asText()).isEqualTo("ACCEPTED");
    // 操作账仍只有 O2 一行；目标端 O2 也只有一次写入
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM operation_ledger WHERE operation_no='O2'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM target_inbox WHERE operation_no='O2'", Integer.class)).isEqualTo(1);
    // 事件序列可见 UNKNOWN -> CONFIRMED
    JsonNode status = getJson("/api/deliveries/O2");
    String types = status.path("events").toString();
    assertThat(types).contains("DELIVERY_UNKNOWN").contains("DELIVERY_CONFIRMED");
  }

  @Test
  void unknownButTargetNeverReceived_query404ThenResendWithSameOperation() {
    seedAcceptedRevision();
    armTimeoutFault();
    assertThat(deliver("O7", "R7", 1, "{\"a\":7}").path("status").asText())
        .isEqualTo("UNKNOWN");
    // 模拟目标端实际未收到（删除其收件箱行）
    jdbc.update("DELETE FROM target_receipt WHERE operation_no='O7'");
    jdbc.update("DELETE FROM target_inbox WHERE operation_no='O7'");

    JsonNode resolved = retry("O7");
    assertThat(resolved.path("status").asText()).isEqualTo("ACCEPTED");
    // 仍然是同一个操作号，没有新建操作
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM operation_ledger WHERE operation_no='O7'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM target_inbox WHERE operation_no='O7'", Integer.class)).isEqualTo(1);
  }

  @Test
  void receiptType_distinguishesReceivedFromDisposalConfirmed() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    JsonNode report = deliver("O1", "R7", 1, "{\"a\":1}");
    assertThat(report.path("receiptType").asText()).isEqualTo("RECEIVED");

    ResponseEntity<JsonNode> disposal =
        deliverRaw("O3", "R7", 1, "DISPOSAL", "{\"action\":\"DISPOSE\"}");
    assertThat(disposal.getBody().path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(disposal.getBody().path("receiptType").asText())
        .isEqualTo("DISPOSAL_CONFIRMED");
  }

  @Test
  void deliveryOfQuarantinedRevision_isUnprocessable() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    ingest("C5", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 4, null, "0.017", "mg/kg", null, null, "PASS")));
    ResponseEntity<JsonNode> response =
        deliverRaw("OX", "R7", 4, "REPORT", "{}");
    assertThat(response.getStatusCode().value()).isEqualTo(422);
  }
}
