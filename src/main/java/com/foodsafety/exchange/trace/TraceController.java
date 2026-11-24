package com.foodsafety.exchange.trace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 食品污染源头候选推导接口。
 * 输出仅为待调查候选，用于风险沟通与调查排期，不构成责任认定。
 */
@RestController
@RequestMapping("/api/trace")
@Tag(name = "污染源头候选推导",
    description = "样品账->成品批次->生产线->配方投料->原料批次；指纹/检出限/不确定度；"
        + "硬约束交集与概率来源模型两种模式；输出待调查候选与反证")
public class TraceController {

  private final TraceService traceService;
  private final TraceDemoService demoService;

  public TraceController(TraceService traceService, TraceDemoService demoService) {
    this.traceService = traceService;
    this.demoService = demoService;
  }

  /** 求值请求；analyte 必填，其余有默认值。 */
  public record EvaluateRequest(String analyte, String mode, Double tolerance,
      Double backgroundRate, String asOf, List<String> sampleKeys, String purpose,
      Boolean useFingerprint) {

    TraceEvalParams toParams() {
      return new TraceEvalParams(analyte, mode,
          tolerance == null ? 0.15 : tolerance, backgroundRate,
          asOf == null || asOf.isBlank() ? null : OffsetDateTime.parse(asOf),
          sampleKeys, useFingerprint == null || useFingerprint);
    }
  }

  @PostMapping("/evaluate")
  @Operation(summary = "推导污染源头候选",
      description = "mode=CONSTRAINT 硬约束交集剪枝后排序；mode=PROBABILISTIC 概率来源模型排序。"
          + "返回待调查候选、被剪枝批次、未解释阳性样品与共用供应商分组（均非责任认定）")
  public TraceResult evaluate(@RequestBody EvaluateRequest request) {
    return traceService.evaluate(request.toParams());
  }

  @GetMapping("/evaluate")
  @Operation(summary = "按查询参数推导候选", description = "GET 便捷入口，参数同 POST")
  public TraceResult evaluateGet(@RequestParam String analyte,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) Double tolerance,
      @RequestParam(required = false) Double backgroundRate,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) Boolean useFingerprint,
      @RequestParam(required = false) List<String> sampleKey) {
    return traceService.evaluate(new EvaluateRequest(analyte, mode, tolerance, backgroundRate,
        asOf, sampleKey, null, useFingerprint).toParams());
  }

  @PostMapping("/cases/{caseId}/versions")
  @Operation(summary = "冻结追溯版本",
      description = "固定本次使用的生产图、实验结果与候选输出（不可变），供风险沟通与复现")
  public Map<String, Object> freeze(@PathVariable String caseId,
      @RequestBody EvaluateRequest request) {
    return traceService.freeze(caseId, request.purpose(), request.toParams());
  }

  @GetMapping("/cases/{caseId}/versions")
  @Operation(summary = "列出追溯版本")
  public List<Map<String, Object>> versions(@PathVariable String caseId) {
    return traceService.listVersions(caseId);
  }

  @GetMapping("/versions")
  @Operation(summary = "列出全部追溯版本")
  public List<Map<String, Object>> allVersions() {
    return traceService.listVersions(null);
  }

  @GetMapping("/cases/{caseId}/versions/{versionId}")
  @Operation(summary = "读取追溯版本", description = "含冻结的生产图、实验结果与候选输出")
  public Map<String, Object> version(@PathVariable String caseId,
      @PathVariable String versionId) {
    return traceService.getVersion(caseId, versionId);
  }

  @GetMapping("/cases/{caseId}/versions/{versionId}/verify")
  @Operation(summary = "复现校验",
      description = "仅用追溯版本冻结的生产图与实验结果重新求值，与冻结输出比较")
  public Map<String, Object> verify(@PathVariable String caseId,
      @PathVariable String versionId) {
    return traceService.verifyVersion(caseId, versionId);
  }

  @GetMapping("/graph")
  @Operation(summary = "生产图与污染谱概览")
  public Map<String, Object> graph() {
    return traceService.graphSummary();
  }

  @PostMapping("/demo/seed")
  @Operation(summary = "导入三工厂共用供应商样例",
      description = "三家工厂共用同一辅料供应商；仅两家在污染可能到达时间内使用同一批次，"
          + "第三家使用相同辅料但指纹不符；含晚到批次与缺失批次记录")
  public Map<String, Object> seed() {
    demoService.seed();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("seeded", true);
    view.put("samples", demoService.sampleKeys());
    view.put("graph", traceService.graphSummary());
    return view;
  }

  @PostMapping("/demo/reset")
  @Operation(summary = "清空追溯样例数据")
  public Map<String, Object> reset() {
    demoService.reset();
    return Map.of("reset", true);
  }

  @PostMapping("/demo/backfill-usage")
  @Operation(summary = "补登缺失的批次记录",
      description = "把未登记原料批次的投料记录补登为指定批次（同辅料），用于验证候选范围收敛")
  public Map<String, Object> backfill(@RequestParam String rawBatchId) {
    int updated = demoService.backfillMissingUsage(rawBatchId);
    return Map.of("rawBatchId", rawBatchId, "updatedUsages", updated,
        "remainingMissingIngredients", demoService.missingUsageIngredients());
  }
}
