package com.foodsafety.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 食品污染源头候选推导：三工厂共用同一供应商样例。
 * 核验到货/投料时间剪枝、指纹证据增减候选排序、缺失批次记录造成的范围扩张，
 * 并确认输出恒为待调查候选而非责任认定。
 */
class TraceSourcingTest extends ExchangeIntegrationTest {

  private JsonNode seed() {
    return postJson("/api/trace/demo/seed", "{}");
  }

  private JsonNode evaluate(String json) {
    return postJson("/api/trace/evaluate", json);
  }

  private int indexOfCandidate(JsonNode result, String rawBatchId) {
    for (int i = 0; i < result.path("candidates").size(); i++) {
      if (rawBatchId.equals(result.path("candidates").get(i).path("rawBatchId").asText())) {
        return i;
      }
    }
    return -1;
  }

  private List<String> rawIds(JsonNode array) {
    List<String> ids = new ArrayList<>();
    array.forEach(n -> ids.add(n.path("rawBatchId").asText()));
    return ids;
  }

  @Test
  void timeAndFingerprintPrune_sharedSupplierIsNotLiability() {
    JsonNode seeded = seed();
    assertThat(seeded.path("seeded").asBoolean()).isTrue();
    assertThat(seeded.path("graph").path("factories").asInt()).isEqualTo(3);

    JsonNode result = evaluate("{\"analyte\":\"LEAD\"}");

    // 恒为待调查候选，绝不写成确定责任
    assertThat(result.path("legalLiability").asBoolean()).isFalse();
    assertThat(result.path("disclaimer").asText()).contains("不构成");
    result.path("candidates").forEach(c ->
        assertThat(c.path("status").asText()).isEqualTo("CANDIDATE_FOR_INVESTIGATION"));

    // 时间剪枝：同供应商晚到批次到货晚于投料，不可达
    List<String> prunedIds = rawIds(result.path("pruned"));
    assertThat(prunedIds).contains("RB-STARCH-LATE");
    JsonNode late = null;
    for (JsonNode p : result.path("pruned")) {
      if ("RB-STARCH-LATE".equals(p.path("rawBatchId").asText())) {
        late = p;
      }
    }
    assertThat(late).isNotNull();
    assertThat(late.path("reason").asText()).isEqualTo("TIME_UNREACHABLE");
    assertThat(late.path("evidence").get(0).asText()).contains("晚于投料结束");

    // 指纹剪枝：第三家工厂用相同辅料但指纹不符
    assertThat(prunedIds).contains("RB-STARCH-OTHER");
    // 该样品无候选可解释，列为未解释并提示全路径被剪枝
    assertThat(result.path("unresolved").get(0).path("sampleKey").asText()).isEqualTo("S-PB3");
    assertThat(result.path("unresolved").get(0).path("reason").asText()).isEqualTo("ALL_PATHS_PRUNED");

    // F1/F2 共用批次 RB-STARCH-OK 位列候选，且被两个样品支持
    int okIndex = indexOfCandidate(result, "RB-STARCH-OK");
    assertThat(okIndex).isZero();
    assertThat(result.path("candidates").get(okIndex).path("supportedBy")).hasSize(2);
    assertThat(result.path("candidates").get(okIndex).path("singleObservation").asBoolean())
        .isFalse();

    // 共用供应商分组存在，且明示不构成同源/责任
    JsonNode shared = null;
    for (JsonNode g : result.path("sharedSupplierGroups")) {
      if ("SUP-SHARED".equals(g.path("supplierId").asText())) {
        shared = g;
      }
    }
    assertThat(shared).isNotNull();
    assertThat(shared.path("note").asText()).contains("不构成同源");
    assertThat(shared.path("factories")).hasSize(3);
  }

  @Test
  void fingerprintEvidenceChangesRanking_noFingerprintWidensScope() {
    seed();
    JsonNode withFp = evaluate("{\"analyte\":\"LEAD\"}");
    JsonNode withoutFp = evaluate("{\"analyte\":\"LEAD\",\"useFingerprint\":false}");

    // 关闭指纹证据后，指纹不符的批次重新进入候选，候选数量增加（范围扩张）
    assertThat(withoutFp.path("candidateCount").asInt())
        .isGreaterThan(withFp.path("candidateCount").asInt());
    assertThat(rawIds(withoutFp.path("candidates"))).contains("RB-STARCH-OTHER");
    assertThat(rawIds(withFp.path("candidates"))).doesNotContain("RB-STARCH-OTHER");

    // 指纹证据只在不确定度容差内比较；开启指纹后该批被作为反证剪枝并说明阈值
    JsonNode other = null;
    for (JsonNode p : withFp.path("pruned")) {
      if ("RB-STARCH-OTHER".equals(p.path("rawBatchId").asText())) {
        other = p;
      }
    }
    assertThat(other).isNotNull();
    assertThat(other.path("evidence").get(0).asText()).contains("阈值");

    // 概率来源模型给出可排序的后验权重，且依赖背景污染率假设
    JsonNode prob = evaluate(
        "{\"analyte\":\"LEAD\",\"mode\":\"PROBABILISTIC\",\"backgroundRate\":0.05}");
    assertThat(prob.path("candidates").get(0).path("rawBatchId").asText())
        .isEqualTo("RB-STARCH-OK");
    assertThat(prob.path("candidates").get(0).path("posterior").asDouble()).isGreaterThan(0.0);
    assertThat(prob.path("assumptions").path("backgroundRateDefaulted").asBoolean()).isFalse();
    JsonNode defaulted = evaluate("{\"analyte\":\"LEAD\",\"mode\":\"PROBABILISTIC\"}");
    assertThat(defaulted.path("assumptions").path("backgroundRateDefaulted").asBoolean()).isTrue();
  }

  @Test
  void missingBatchRecordExpandsScope_backfillNarrows() {
    seed();
    JsonNode before = evaluate("{\"analyte\":\"LEAD\"}");
    // 缺失批次记录：辅料投料未登记原料批次，按同辅料展开为多个候选并标记范围扩张
    int em1 = indexOfCandidate(before, "RB-EM-01");
    assertThat(em1).isGreaterThanOrEqualTo(0);
    JsonNode em = before.path("candidates").get(em1);
    assertThat(em.path("viaMissingRecord").asBoolean()).isTrue();
    assertThat(em.path("scopeExpanded").asBoolean()).isTrue();
    assertThat(before.path("status").asText()).isEqualTo("SCOPE_EXPANDED");
    // 展开候选为单次阳性支持，明确标注不足以认定同源
    assertThat(em.path("singleObservation").asBoolean()).isTrue();

    // 补登批次记录后，展开候选收敛为单一直接路径，范围不再扩张
    postJson("/api/trace/demo/backfill-usage?rawBatchId=RB-EM-01", "{}");
    JsonNode after = evaluate("{\"analyte\":\"LEAD\"}");
    int em1After = indexOfCandidate(after, "RB-EM-01");
    assertThat(em1After).isGreaterThanOrEqualTo(0);
    assertThat(after.path("candidates").get(em1After).path("viaMissingRecord").asBoolean())
        .isFalse();
    assertThat(indexOfCandidate(after, "RB-EM-02")).isNegative();
  }

  @Test
  void traceVersionFreezesInputsAndIsReproducible() {
    seed();
    JsonNode frozen = postJson("/api/trace/cases/CASE-LEAD-0326/versions",
        "{\"analyte\":\"LEAD\",\"purpose\":\"RISK_COMMUNICATION\"}");
    String versionId = frozen.path("versionId").asText();
    assertThat(versionId).startsWith("TV-");
    assertThat(frozen.path("graphSha256").asText()).hasSize(64);
    assertThat(frozen.path("spectraSha256").asText()).hasSize(64);
    assertThat(frozen.path("inputsSha256").asText()).hasSize(64);
    assertThat(frozen.path("legalLiability").asBoolean()).isFalse();
    assertThat(frozen.path("purpose").asText()).isEqualTo("RISK_COMMUNICATION");

    // 仅用冻结的生产图 + 实验结果重新求值，与冻结输出一致
    JsonNode verify = getJson(
        "/api/trace/cases/CASE-LEAD-0326/versions/" + versionId + "/verify");
    assertThat(verify.path("reproducible").asBoolean()).isTrue();

    // 读取版本含冻结的生产图与实验结果
    JsonNode read = getJson("/api/trace/cases/CASE-LEAD-0326/versions/" + versionId);
    assertThat(read.path("graph").path("rawBatches").size()).isGreaterThan(0);
    assertThat(read.path("spectra").size()).isGreaterThan(0);

    // 追溯版本不可修改：触发器拒绝 UPDATE
    boolean blocked = false;
    try {
      jdbc.update("UPDATE trace_version SET purpose = 'TAMPERED' WHERE version_id = ?", versionId);
    } catch (Exception e) {
      blocked = true;
    }
    assertThat(blocked).isTrue();
  }

  @Test
  void asOfWindowExcludesLaterResults() {
    seed();
    // asOf 早于 SP-2 的测量时间（2026-03-20T10:10Z）：仅 S-PB1 阳性，候选只来自 F1
    JsonNode early = evaluate("{\"analyte\":\"LEAD\",\"asOf\":\"2026-03-20T10:06:00Z\"}");
    assertThat(early.path("positiveSamples").asInt()).isEqualTo(1);
    assertThat(indexOfCandidate(early, "RB-STARCH-OK")).isZero();
    assertThat(early.path("candidates").get(0).path("supportedBy")).hasSize(1);
    assertThat(early.path("candidates").get(0).path("singleObservation").asBoolean()).isTrue();

    // 其后时点纳入 S-PB2 -> 同批次被两个样品支持
    JsonNode later = evaluate("{\"analyte\":\"LEAD\"}");
    assertThat(later.path("positiveSamples").asInt()).isEqualTo(3);
    assertThat(later.path("candidates").get(0).path("supportedBy")).hasSize(2);
  }

  @Test
  void evaluateRejectsUnknownMode() {
    seed();
    Map<String, Object> body = Map.of("analyte", "LEAD", "mode", "MAGIC");
    org.springframework.http.ResponseEntity<JsonNode> resp = authed().postForEntity(
        "/api/trace/evaluate",
        new org.springframework.http.HttpEntity<>(body, jsonHeadersPublic()),
        JsonNode.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(400);
  }

  private org.springframework.http.HttpHeaders jsonHeadersPublic() {
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }
}
