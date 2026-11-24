package com.foodsafety.exchange.trace;

import java.util.List;
import java.util.Map;

/**
 * 追溯求值输出：仅是“待调查候选”，不是责任认定。
 * 结果区分候选、被硬约束剪枝的批次、以及无法解释的阳性样品（需另行调查）。
 */
public record TraceResult(
    String caseId,
    String analyte,
    String mode,
    String status,
    boolean legalLiability,
    String disclaimer,
    int positiveSamples,
    int candidateCount,
    List<Candidate> candidates,
    List<Pruned> pruned,
    List<Unresolved> unresolved,
    List<SharedSupplierGroup> sharedSupplierGroups,
    Map<String, Object> assumptions) {

  /** 候选来源批次。状态恒为“待调查候选”，并标注是否为单次阳性等弱证据。 */
  public record Candidate(
      int rank,
      String rawBatchId,
      String ingredientCode,
      String supplierId,
      double score,
      Double posterior,
      boolean scopeExpanded,
      boolean viaMissingRecord,
      boolean fingerprintUnknown,
      List<String> supportedBy,
      List<String> expandedBy,
      List<String> contradictedBy,
      List<String> negativeOnBatch,
      List<Evidence> evidence,
      List<CounterEvidence> counterEvidence,
      boolean singleObservation,
      String status,
      String note) {}

  /** 支持候选的路径证据：样品->成品批次->投料->原料批次。 */
  public record Evidence(
      String sampleKey, String productBatchId, String rawBatchId, String linkType,
      boolean viaMissingRecord, double similarity) {}

  /** 反证：指纹不符、时间不可达、阴性对照、缺失记录等。 */
  public record CounterEvidence(String sampleKey, String type, String detail) {}

  /** 被硬约束剪枝的批次：时间不可达或指纹不符。 */
  public record Pruned(String rawBatchId, String ingredientCode, String supplierId, String reason,
      List<String> evidence) {}

  /** 无法由任何候选解释的阳性样品，需要在图形/记录补齐后另行调查。 */
  public record Unresolved(String sampleKey, String productBatchId, String reason) {}

  /** 共用供应商分组：仅表示共同采购渠道，明示不构成同源证据。 */
  public record SharedSupplierGroup(String supplierId, List<String> rawBatches,
      List<String> factories, String note) {}
}
