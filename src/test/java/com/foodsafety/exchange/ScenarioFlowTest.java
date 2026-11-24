package com.foodsafety.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 题面样例场景全流程：
 * C1/S17/R7/修订1/O1 已受理 -> 修订2 显式取代/O2 超时 -> O2 重发先查询后收敛 ->
 * C1 重传不回滚 -> 修订3 走 XML2 新规范 -> 事件重放与版本投影核验。
 */
class ScenarioFlowTest extends ExchangeIntegrationTest {

  @Test
  void fullSampleScenario_replayAndProjectionAgree() {
    // 1) C1：S17/R7/修订1，CSV1
    JsonNode c1 = ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", "0.005", "mg/kg", "PASS")));
    assertThat(c1.path("specVersion").asText()).isEqualTo("CSV1");
    assertThat(c1.path("entries").get(0).path("status").asText()).isEqualTo("ACCEPTED");

    // 2) O1 -> 已受理，回执 RECEIVED
    JsonNode o1 = deliver("O1", "R7", 1, "{\"k\":\"v1\"}");
    assertThat(o1.path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(o1.path("receiptType").asText()).isEqualTo("RECEIVED");

    // 3) C2：修订2 显式 supersedes 1
    JsonNode c2 = ingest("C2", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 2, "1", "0.019", "mg/kg", "0.005", "mg/kg", "PASS")));
    assertThat(c2.path("entries").get(0).path("status").asText()).isEqualTo("ACCEPTED");
    JsonNode proj = getJson("/api/projections/reports/R7");
    assertThat(proj.path("head").asInt()).isEqualTo(2);
    assertThat(statusOfRevision(proj, 1)).isEqualTo("SUPERSEDED");

    // 4) O2 超时 -> UNKNOWN
    armTimeoutFault();
    JsonNode o2 = deliver("O2", "R7", 2, "{\"k\":\"v2\"}");
    assertThat(o2.path("status").asText()).isEqualTo("UNKNOWN");
    long unknownSeq = o2.path("eventSeq").asLong();

    // 5) 重发 O2：先查询目标 -> 收敛 ACCEPTED；目标端只有一次写入
    JsonNode retried = retry("O2");
    assertThat(retried.path("status").asText()).isEqualTo("ACCEPTED");
    Integer targetO2Count = jdbc.queryForObject(
        "SELECT count(*) FROM target_inbox WHERE operation_no = 'O2'", Integer.class);
    assertThat(targetO2Count).isEqualTo(1);

    // 时点重放：超时时刻 UNKNOWN，当前 ACCEPTED
    assertThat(getJson("/api/replay/operations/O2?asOfSeq=" + unknownSeq)
        .path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(getJson("/api/replay/operations/O2").path("status").asText())
        .isEqualTo("ACCEPTED");

    // 6) C1 重传：重复到达，不回滚
    JsonNode retransmit = ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", "0.005", "mg/kg", "PASS")));
    assertThat(retransmit.path("retransmitted").asBoolean()).isTrue();
    assertThat(retransmit.path("entries").get(0).path("status").asText())
        .isEqualTo("DUPLICATE_ARRIVAL");
    assertThat(getJson("/api/projections/reports/R7").path("head").asInt()).isEqualTo(2);

    // 7) 修订3：新批次 XML2 规范
    String xml3 = xmlReport("S17", "R7", 3, "2", "0.018", "mg/kg", "0.004", "mg/kg");
    JsonNode c3 = ingest("C3", "B2026-11", "lab-alpha", LAB_SECRET,
        xml3.getBytes(), "[{\"id\":\"att-9\",\"sha256\":\"x\"}]");
    assertThat(c3.path("specVersion").asText()).isEqualTo("XML2");
    assertThat(c3.path("entries").get(0).path("status").asText()).isEqualTo("ACCEPTED");
    proj = getJson("/api/projections/reports/R7");
    assertThat(proj.path("head").asInt()).isEqualTo(3);
    assertThat(statusOfRevision(proj, 2)).isEqualTo("SUPERSEDED");
    assertThat(specOfRevision(proj, 1)).isEqualTo("CSV1");
    assertThat(specOfRevision(proj, 3)).isEqualTo("XML2");

    // 8) 重放与投影一致；重建一致
    assertThat(getJson("/api/replay/reports/R7/verify").path("consistent").asBoolean()).isTrue();
    JsonNode rebuild = postJson("/api/projections/rebuild", "{}");
    assertThat(rebuild.path("consistent").asBoolean()).isTrue();
    assertThat(rebuild.path("projectionDifferences").size()).isZero();

    // 9) 对账证据链完整
    JsonNode evidence = getJson("/api/reconciliation/containers/C1");
    assertThat(evidence.path("container").path("containerId").asText()).isEqualTo("C1");
    assertThat(evidence.path("entries").get(0).path("reportNo").asText()).isEqualTo("R7");
    JsonNode opEvidence = getJson("/api/reconciliation/operations/O2");
    assertThat(opEvidence.path("operation").path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(opEvidence.path("operation").path("receipts").size()).isGreaterThan(0);
  }

  private String statusOfRevision(JsonNode reportView, int revisionNo) {
    for (JsonNode rev : reportView.path("revisions")) {
      if (rev.path("revisionNo").asInt() == revisionNo) {
        return rev.path("status").asText();
      }
    }
    return null;
  }

  private String specOfRevision(JsonNode reportView, int revisionNo) {
    for (JsonNode rev : reportView.path("revisions")) {
      if (rev.path("revisionNo").asInt() == revisionNo) {
        return rev.path("specVersion").asText();
      }
    }
    return null;
  }
}
