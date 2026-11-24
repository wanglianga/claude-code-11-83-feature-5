package com.foodsafety.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** 重放窗口、投影重建差异检测与不可变库触发器测试。 */
class ProjectionRebuildTest extends ExchangeIntegrationTest {

  @Test
  void replayAtTimeWindows_showsHeadTransition() {
    // 修订1 受理
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    long seqAfterRev1 = jdbc.queryForObject("SELECT max(event_seq) FROM business_event", Long.class);

    // 修订2 受理
    ingest("C2", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 2, "1", "0.019", "mg/kg", null, null, "PASS")));

    // 窗口截止于修订1之后 -> head=1
    JsonNode then = getJson("/api/replay/reports/R7?asOfSeq=" + seqAfterRev1);
    assertThat(then.path("head").asInt()).isEqualTo(1);
    assertThat(then.path("eventsScanned").asInt()).isLessThan(
        getJson("/api/replay/reports/R7").path("eventsScanned").asInt());

    // 当前 -> head=2
    assertThat(getJson("/api/replay/reports/R7").path("head").asInt()).isEqualTo(2);
  }

  @Test
  void rebuildAfterProjectionDrift_reportsAndCorrectsDifference() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    ingest("C2", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 2, "1", "0.019", "mg/kg", null, null, "PASS")));
    assertThat(getJson("/api/replay/reports/R7/verify").path("consistent").asBoolean()).isTrue();

    // 人为制造投影漂移
    jdbc.update("UPDATE report_head_projection SET head_revision = 1 WHERE report_no = 'R7'");
    assertThat(getJson("/api/replay/reports/R7/verify").path("consistent").asBoolean()).isFalse();

    // 重建：报告差异并修正
    JsonNode report = postJson("/api/projections/rebuild", "{}");
    assertThat(report.path("projectionDifferences").size()).isGreaterThan(0);
    assertThat(report.path("projectionDifferences").get(0).asText()).contains("H:R7");
    // 重建报告本身记录了差异，故 consistent=false（差异已列出并已修正）
    assertThat(report.path("consistent").asBoolean()).isFalse();

    // 修正后投影与重放一致，再次重建无差异
    assertThat(getJson("/api/projections/reports/R7").path("head").asInt()).isEqualTo(2);
    assertThat(getJson("/api/replay/reports/R7/verify").path("consistent").asBoolean()).isTrue();
    JsonNode second = postJson("/api/projections/rebuild", "{}");
    assertThat(second.path("projectionDifferences").size()).isZero();
    assertThat(second.path("consistent").asBoolean()).isTrue();
  }

  @Test
  void immutableTables_rejectUpdateAndDelete() {
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE inbound_envelope SET sender = 'evil' WHERE container_id = 'C1'"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM business_event"))
        .isInstanceOf(DataAccessException.class);

    // 原始值未变
    String sender = jdbc.queryForObject(
        "SELECT sender FROM inbound_envelope WHERE container_id = 'C1'", String.class);
    assertThat(sender).isEqualTo("lab-alpha");
  }

  @Test
  void sampleKeyIsolation_doesNotMergeSimilarReports() {
    // 两个不同样品键，即使报告号相似也不合并
    ingest("C1", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S17", "R7", 1, null, "0.02", "mg/kg", null, null, "PASS")));
    ingest("C2", "B2026-01", "lab-alpha", LAB_SECRET,
        csv(csvRow("S18", "R7X", 1, null, "0.02", "mg/kg", null, null, "PASS")));

    JsonNode s17 = getJson("/api/reconciliation/samples/S17");
    assertThat(s17.path("heads").size()).isEqualTo(1);
    assertThat(s17.path("heads").get(0).path("reportNo").asText()).isEqualTo("R7");
    JsonNode s18 = getJson("/api/reconciliation/samples/S18");
    assertThat(s18.path("heads").get(0).path("reportNo").asText()).isEqualTo("R7X");
  }
}
