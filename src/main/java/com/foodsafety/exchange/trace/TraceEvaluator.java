package com.foodsafety.exchange.trace;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 污染源头候选路径求值器。
 *
 * <p>对每个阳性样品沿「样品 → 成品批次 → 配方投料 → 原料批次」求值，先以硬约束交集剔除不可能批次
 * （到货/投料时间可达性、指纹相容度），再对剩余路径排序；也可切换为概率来源模型
 * （以背景污染率作为先验与似然基准）。两种输出都只是待调查候选，不是责任认定。</p>
 *
 * <p>缺失批次记录（投料记录未登记原料批次）时，按同辅料展开到可能原料批次并标记范围扩张；
 * 时间边界缺失时不强行剪枝，而是放宽候选并标注。</p>
 */
@Component
public class TraceEvaluator {

  private static final String CANDIDATE_STATUS = "CANDIDATE_FOR_INVESTIGATION";
  private static final String CANDIDATE_NOTE = "待调查候选，需现场核查补充证据；不得作为责任认定";
  private static final String DISCLAIMER =
      "本结果仅为基于现有生产图与实验结果的待调查候选，用于风险沟通与调查排期，"
          + "不构成对任何企业或批次的责任认定；共用供应商或单次阳性均不足以认定同源。";
  private static final double DEFAULT_BACKGROUND_RATE = 0.05;

  public TraceResult evaluate(TraceSnapshot snap, TraceEvalParams params) {
    Map<String, TraceSnapshot.FactoryRec> factories = snap.factoryIndex();
    Map<String, TraceSnapshot.LineRec> lines = snap.lineIndex();
    Map<String, TraceSnapshot.ProductBatchRec> productBatches = snap.productBatchIndex();
    Map<String, TraceSnapshot.RawBatchRec> rawBatches = snap.rawBatchIndex();
    Map<String, TraceSnapshot.SampleRec> samples = snap.sampleIndex();
    Map<String, List<TraceSnapshot.UsageRec>> usagesByBatch = snap.usagesByProductBatch();

    Set<String> only = params.onlySampleKeys() == null ? null : new LinkedHashSet<>(params.onlySampleKeys());
    List<TraceSnapshot.SpectrumRec> spectra = snap.spectraForAnalyte(params.analyte()).stream()
        .filter(s -> params.asOf() == null || !s.measuredAt().isAfter(params.asOf()))
        .filter(s -> only == null || only.contains(s.sampleKey()))
        .toList();

    List<TraceSnapshot.SpectrumRec> positives = spectra.stream()
        .filter(s -> "POSITIVE".equalsIgnoreCase(s.polarity())).toList();
    List<TraceSnapshot.SpectrumRec> negatives = spectra.stream()
        .filter(s -> "NEGATIVE".equalsIgnoreCase(s.polarity())).toList();

    Aggregator agg = new Aggregator();
    Set<String> positiveSampleKeys = new LinkedHashSet<>();
    Set<String> resolvedPositiveSamples = new LinkedHashSet<>();

    for (TraceSnapshot.SpectrumRec spectrum : positives) {
      positiveSampleKeys.add(spectrum.sampleKey());
      TraceSnapshot.SampleRec sample = samples.get(spectrum.sampleKey());
      if (sample == null) {
        agg.unresolved.add(new TraceResult.Unresolved(spectrum.sampleKey(), null, "SAMPLE_NOT_IN_LEDGER"));
        continue;
      }
      TraceSnapshot.ProductBatchRec pb = productBatches.get(sample.productBatchId());
      if (pb == null) {
        agg.unresolved.add(new TraceResult.Unresolved(spectrum.sampleKey(),
            sample.productBatchId(), "PRODUCT_BATCH_NOT_FOUND"));
        continue;
      }
      boolean anyOk = evaluatePositivePaths(spectrum, sample, pb, usagesByBatch, rawBatches,
          params, agg);
      if (anyOk) {
        resolvedPositiveSamples.add(spectrum.sampleKey());
      } else {
        boolean hadPath = !usagesByBatch.getOrDefault(pb.productBatchId(), List.of()).isEmpty();
        agg.unresolved.add(new TraceResult.Unresolved(spectrum.sampleKey(),
            pb.productBatchId(), hadPath ? "ALL_PATHS_PRUNED" : "NO_REACHABLE_SOURCE_PATH"));
      }
    }

    // 阴性样品：只用于对已达候选的批次形成反证（单次阴性不剪枝，只降低排序）
    for (TraceSnapshot.SpectrumRec spectrum : negatives) {
      TraceSnapshot.SampleRec sample = samples.get(spectrum.sampleKey());
      if (sample == null) {
        continue;
      }
      TraceSnapshot.ProductBatchRec pb = productBatches.get(sample.productBatchId());
      if (pb == null) {
        continue;
      }
      for (String rawBatchId : reachableRawBatches(pb, usagesByBatch, rawBatches, null)) {
        agg.negatives.computeIfAbsent(rawBatchId, k -> new LinkedHashSet<>())
            .add(spectrum.sampleKey());
      }
    }

    double backgroundRate = params.backgroundRate() == null
        ? DEFAULT_BACKGROUND_RATE : params.backgroundRate();
    boolean backgroundDefaulted = params.backgroundRate() == null;

    // 汇总候选 / 剪枝 / 未解释
    for (Map.Entry<String, Set<String>> e : agg.negatives.entrySet()) {
      BatchStats st = agg.byBatch.get(e.getKey());
      if (st != null) {
        st.negativeCount = e.getValue().size();
      }
    }
    List<BatchStats> stats = agg.sorted();
    List<TraceResult.Candidate> candidates = new ArrayList<>();
    List<TraceResult.Pruned> pruned = new ArrayList<>();
    int rank = 0;
    // 先算概率权重以便归一化
    Map<String, Double> weights = new LinkedHashMap<>();
    double weightSum = 0;
    for (BatchStats st : stats) {
      if (!st.okPaths.isEmpty()) {
        double w = probabilisticWeight(st, backgroundRate);
        weights.put(st.rawBatchId, w);
        weightSum += w;
      }
    }
    for (BatchStats st : stats) {
      if (st.okPaths.isEmpty()) {
        if (st.hadPositivePath) {
          TraceSnapshot.RawBatchRec raw = rawBatches.get(st.rawBatchId);
          pruned.add(new TraceResult.Pruned(st.rawBatchId,
              raw == null ? null : raw.ingredientCode(), raw == null ? null : raw.supplierId(),
              primaryReason(st), new ArrayList<>(st.pruneNotes)));
        }
        continue;
      }
      TraceSnapshot.RawBatchRec raw = rawBatches.get(st.rawBatchId);
      double avgSim = st.simSum / Math.max(1, st.simCount);
      Set<String> negativeSamples = agg.negatives.getOrDefault(st.rawBatchId, Set.of());
      List<String> contradicted = new ArrayList<>(st.contradictedNotes);
      for (String negSample : negativeSamples) {
        contradicted.add("阴性观测 " + negSample + "：削弱候选但不足以排除");
      }
      boolean single = st.supportSamples.size() == 1;
      boolean fpUnknown = st.simCount == 0;
      double score;
      Double posterior = null;
      if (params.isProbabilistic()) {
        double share = weightSum > 0 ? weights.getOrDefault(st.rawBatchId, 0.0) / weightSum : 0.0;
        posterior = round(share, 4);
        score = round(share, 4);
      } else {
        score = round(constraintScore(st, avgSim, negativeSamples.size()), 4);
      }
      rank++;
      candidates.add(new TraceResult.Candidate(
          rank,
          st.rawBatchId,
          raw == null ? null : raw.ingredientCode(),
          raw == null ? null : raw.supplierId(),
          score,
          posterior,
          st.missingRecordPaths > 0 || st.boundaryMissing,
          st.missingRecordPaths > 0,
          fpUnknown,
          new ArrayList<>(st.supportSamples),
          new ArrayList<>(st.expandedBy),
          contradicted,
          new ArrayList<>(negativeSamples),
          new ArrayList<>(st.evidence),
          buildCounterEvidence(st, negativeSamples, fpUnknown),
          single,
          CANDIDATE_STATUS,
          CANDIDATE_NOTE));
    }
    // 按分数排序，保证排序稳定可复现（同分按批次号）
    candidates.sort(Comparator
        .comparingDouble(TraceResult.Candidate::score).reversed()
        .thenComparing(TraceResult.Candidate::rawBatchId));
    List<TraceResult.Candidate> reranked = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      TraceResult.Candidate c = candidates.get(i);
      reranked.add(new TraceResult.Candidate(i + 1, c.rawBatchId(), c.ingredientCode(),
          c.supplierId(), c.score(), c.posterior(), c.scopeExpanded(), c.viaMissingRecord(),
          c.fingerprintUnknown(), c.supportedBy(), c.expandedBy(), c.contradictedBy(),
          c.negativeOnBatch(), c.evidence(), c.counterEvidence(), c.singleObservation(),
          c.status(), c.note()));
    }

    List<TraceResult.SharedSupplierGroup> groups = sharedSupplierGroups(snap, stats, candidates,
        productBatches, usagesByBatch, lines, factories);

    boolean scopeExpanded = reranked.stream().anyMatch(TraceResult.Candidate::scopeExpanded);
    String status = reranked.isEmpty() ? "NO_CANDIDATE"
        : scopeExpanded ? "SCOPE_EXPANDED" : "CANDIDATES_ONLY";

    Map<String, Object> assumptions = new LinkedHashMap<>();
    assumptions.put("mode", params.mode());
    assumptions.put("tolerance", params.tolerance());
    assumptions.put("fingerprintEvidenceUsed", params.useFingerprint());
    assumptions.put("fingerprintRule",
        "余弦相似度 >= 1-(tolerance+相对不确定度)，阈值裁剪到 [0.40,0.95]；指纹缺失视为未知、不剪枝");
    assumptions.put("timeRule",
        "原料采购起始/到货起始晚于投料结束（或成品批次生产结束）即不可达；边界缺失不剪枝而扩张候选");
    assumptions.put("missingRecordRule",
        "投料记录未登记原料批次时，按同辅料展开到时间可达的候选批次，并标记范围扩张");
    assumptions.put("backgroundRateUsed", round(backgroundRate, 4));
    assumptions.put("backgroundRateDefaulted", backgroundDefaulted);
    assumptions.put("asOf", params.asOf() == null ? null : params.asOf().toString());
    assumptions.put("positiveSamples", positiveSampleKeys.size());
    assumptions.put("resolvedPositiveSamples", resolvedPositiveSamples.size());
    assumptions.put("sharedSupplierRule",
        "共用供应商只用于汇总采购渠道，绝不作为同源或责任依据");
    assumptions.put("outputNature", "仅输出待调查候选，非法律定责");

    return new TraceResult(
        null,
        params.analyte(),
        params.mode(),
        status,
        false,
        DISCLAIMER,
        positiveSampleKeys.size(),
        reranked.size(),
        reranked,
        pruned,
        agg.unresolved,
        groups,
        assumptions);
  }

  // ---------------------------------------------------------------- 路径求值

  private boolean evaluatePositivePaths(TraceSnapshot.SpectrumRec spectrum,
      TraceSnapshot.SampleRec sample, TraceSnapshot.ProductBatchRec pb,
      Map<String, List<TraceSnapshot.UsageRec>> usagesByBatch,
      Map<String, TraceSnapshot.RawBatchRec> rawBatches, TraceEvalParams params,
      Aggregator agg) {
    List<TraceSnapshot.UsageRec> usages =
        usagesByBatch.getOrDefault(pb.productBatchId(), List.of());
    boolean anyOk = false;
    for (TraceSnapshot.UsageRec usage : usages) {
      List<String> targets = new ArrayList<>();
      boolean missingRecord = usage.rawBatchId() == null;
      if (!missingRecord) {
        targets.add(usage.rawBatchId());
      } else {
        // 缺失批次记录：按同辅料展开到全部候选原料批次
        for (TraceSnapshot.RawBatchRec candidate : rawBatches.values()) {
          if (candidate.ingredientCode().equals(usage.ingredientCode())) {
            targets.add(candidate.rawBatchId());
          }
        }
      }
      for (String rawBatchId : targets) {
        TraceSnapshot.RawBatchRec raw = rawBatches.get(rawBatchId);
        if (raw == null) {
          agg.unresolved.add(new TraceResult.Unresolved(spectrum.sampleKey(),
              pb.productBatchId(), "RAW_BATCH_NOT_FOUND:" + rawBatchId));
          continue;
        }
        agg.markPositive(rawBatchId);
        String timeReason = timeUnreachableReason(raw, usage, pb);
        Double sim = Fingerprints.cosine(spectrum.fingerprint(), raw.fingerprint());
        double threshold = Fingerprints.compatibilityThreshold(params.tolerance(),
            spectrum.relativeUncertainty() == null ? null
                : spectrum.relativeUncertainty().doubleValue());
        boolean fpMismatch = params.useFingerprint() && sim != null && sim < threshold;

        if (timeReason != null) {
          agg.addPrune(rawBatchId, "TIME_UNREACHABLE",
              "样品 " + spectrum.sampleKey() + " 路径不可达：" + timeReason);
          continue;
        }
        if (fpMismatch) {
          agg.addPrune(rawBatchId, "FINGERPRINT_INCOMPATIBLE",
              String.format("样品 %s 与批次 %s 指纹余弦=%.3f < 阈值 %.3f",
                  spectrum.sampleKey(), rawBatchId, sim, threshold));
          continue;
        }
        boolean boundaryMissing = purchaseBoundaryMissing(raw);
        agg.addOkPath(rawBatchId, spectrum.sampleKey(), pb.productBatchId(), missingRecord,
            sim, boundaryMissing);
        anyOk = true;
      }
    }
    return anyOk;
  }

  /** 到货/采购时间可达性：返回不可达原因，可达返回 null。 */
  private String timeUnreachableReason(TraceSnapshot.RawBatchRec raw,
      TraceSnapshot.UsageRec usage, TraceSnapshot.ProductBatchRec pb) {
    OffsetDateTime end = usage.usedTo() != null ? usage.usedTo() : pb.productionEnd();
    if (raw.purchaseFrom() != null && raw.purchaseFrom().isAfter(end)) {
      return "采购起始 " + raw.purchaseFrom() + " 晚于投料结束 " + end;
    }
    if (raw.arrivalFrom() != null && raw.arrivalFrom().isAfter(end)) {
      return "到货起始 " + raw.arrivalFrom() + " 晚于投料结束 " + end;
    }
    return null;
  }

  private boolean purchaseBoundaryMissing(TraceSnapshot.RawBatchRec raw) {
    return raw.arrivalFrom() == null || raw.purchaseFrom() == null;
  }

  /** 从成品批次出发可达的原料批次（用于阴性反证；不比较指纹）。 */
  private Set<String> reachableRawBatches(TraceSnapshot.ProductBatchRec pb,
      Map<String, List<TraceSnapshot.UsageRec>> usagesByBatch,
      Map<String, TraceSnapshot.RawBatchRec> rawBatches, TraceEvalParams params) {
    Set<String> reachable = new LinkedHashSet<>();
    for (TraceSnapshot.UsageRec usage : usagesByBatch.getOrDefault(pb.productBatchId(), List.of())) {
      if (usage.rawBatchId() != null) {
        TraceSnapshot.RawBatchRec raw = rawBatches.get(usage.rawBatchId());
        if (raw != null && timeUnreachableReason(raw, usage, pb) == null) {
          reachable.add(usage.rawBatchId());
        }
      } else {
        for (TraceSnapshot.RawBatchRec raw : rawBatches.values()) {
          if (raw.ingredientCode().equals(usage.ingredientCode())
              && timeUnreachableReason(raw, usage, pb) == null) {
            reachable.add(raw.rawBatchId());
          }
        }
      }
    }
    return reachable;
  }

  private double constraintScore(BatchStats st, double avgSim, int negatives) {
    double score = st.supportSamples.size()
        + (st.simCount == 0 ? 0 : avgSim) * 0.5
        - negatives * 0.35
        - (st.missingRecordPaths > 0 ? 0.10 : 0)
        - (st.boundaryMissing ? 0.05 : 0);
    return Math.max(0.0, score);
  }

  /**
   * 概率来源模型权重：先验 = 背景污染率；阳性路径按指纹相似度提升似然，
   * 阴性路径按背景污染率降低权重。仅用于候选间相对排序，不是绝对概率。
   */
  private double probabilisticWeight(BatchStats st, double backgroundRate) {
    double p = Math.max(0.01, Math.min(0.99, backgroundRate));
    double weight = p / (1 - p);
    for (double sim : st.posSims) {
      double lr = Math.max(0.20, sim) / Math.max(0.05, p);
      weight *= lr;
    }
    for (int i = 0; i < st.negativeCount; i++) {
      weight *= Math.max(0.05, p);
    }
    if (st.missingRecordPaths > 0) {
      weight *= 0.8;
    }
    return Math.min(weight, 1e9);
  }

  private List<TraceResult.CounterEvidence> buildCounterEvidence(BatchStats st,
      Set<String> negativeSamples, boolean fpUnknown) {
    List<TraceResult.CounterEvidence> list = new ArrayList<>();
    for (String sampleKey : negativeSamples) {
      list.add(new TraceResult.CounterEvidence(sampleKey, "NEGATIVE_OBSERVATION",
          "该样品对目标污染物为阴性，削弱但不足以排除候选"));
    }
    for (String note : st.expandedBy) {
      list.add(new TraceResult.CounterEvidence(null, "MISSING_BATCH_RECORD", note));
    }
    if (st.boundaryMissing) {
      list.add(new TraceResult.CounterEvidence(null, "BOUNDARY_INCOMPLETE",
          "采购/到货时间边界缺失，无法进一步剪枝，候选范围扩张"));
    }
    if (fpUnknown) {
      list.add(new TraceResult.CounterEvidence(null, "FINGERPRINT_UNKNOWN",
          "缺少可用指纹，未做相容度剪枝"));
    }
    if (st.supportSamples.size() == 1) {
      list.add(new TraceResult.CounterEvidence(
          st.supportSamples.iterator().next(), "SINGLE_OBSERVATION",
          "仅有单次阳性支持，不足以认定为同源或责任"));
    }
    return list;
  }

  private String primaryReason(BatchStats st) {
    if (st.pruneReasons.contains("TIME_UNREACHABLE")) {
      return "TIME_UNREACHABLE";
    }
    if (st.pruneReasons.contains("FINGERPRINT_INCOMPATIBLE")) {
      return "FINGERPRINT_INCOMPATIBLE";
    }
    return st.pruneReasons.isEmpty() ? "NO_PATH" : st.pruneReasons.iterator().next();
  }

  // ---------------------------------------------------------------- 共用供应商

  private List<TraceResult.SharedSupplierGroup> sharedSupplierGroups(TraceSnapshot snap,
      List<BatchStats> stats, List<TraceResult.Candidate> candidates,
      Map<String, TraceSnapshot.ProductBatchRec> productBatches,
      Map<String, List<TraceSnapshot.UsageRec>> usagesByBatch,
      Map<String, TraceSnapshot.LineRec> lines,
      Map<String, TraceSnapshot.FactoryRec> factories) {
    Set<String> considered = new LinkedHashSet<>();
    candidates.forEach(c -> considered.add(c.rawBatchId()));
    stats.stream().filter(s -> s.hadPositivePath).forEach(s -> considered.add(s.rawBatchId));
    Map<String, TraceSnapshot.RawBatchRec> rawIndex = snap.rawBatchIndex();

    // rawBatch -> 使用它的工厂集合：缺失批次记录时按同辅料展开，也视作可能使用方
    Map<String, Set<String>> factoriesByRaw = new LinkedHashMap<>();
    for (Map.Entry<String, List<TraceSnapshot.UsageRec>> e : usagesByBatch.entrySet()) {
      TraceSnapshot.ProductBatchRec pb = productBatches.get(e.getKey());
      if (pb == null) {
        continue;
      }
      TraceSnapshot.LineRec line = lines.get(pb.lineId());
      if (line == null) {
        continue;
      }
      for (TraceSnapshot.UsageRec usage : e.getValue()) {
        if (usage.rawBatchId() != null) {
          factoriesByRaw.computeIfAbsent(usage.rawBatchId(), k -> new LinkedHashSet<>())
              .add(line.factoryId());
        } else {
          for (TraceSnapshot.RawBatchRec raw : snap.rawBatches()) {
            if (raw.ingredientCode().equals(usage.ingredientCode())) {
              factoriesByRaw.computeIfAbsent(raw.rawBatchId(), k -> new LinkedHashSet<>())
                  .add(line.factoryId());
            }
          }
        }
      }
    }

    Map<String, List<String>> bySupplier = new LinkedHashMap<>();
    Map<String, Set<String>> supplierFactories = new LinkedHashMap<>();
    for (String rawBatchId : considered) {
      TraceSnapshot.RawBatchRec raw = rawIndex.get(rawBatchId);
      if (raw == null) {
        continue;
      }
      bySupplier.computeIfAbsent(raw.supplierId(), k -> new ArrayList<>()).add(rawBatchId);
      supplierFactories.computeIfAbsent(raw.supplierId(), k -> new LinkedHashSet<>())
          .addAll(factoriesByRaw.getOrDefault(rawBatchId, Set.of()));
    }
    List<TraceResult.SharedSupplierGroup> groups = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : bySupplier.entrySet()) {
      if (e.getValue().size() < 1) {
        continue;
      }
      groups.add(new TraceResult.SharedSupplierGroup(e.getKey(), e.getValue(),
          new ArrayList<>(supplierFactories.getOrDefault(e.getKey(), Set.of())),
          "共用供应商仅表明共同采购渠道，不构成同源证据，不得据此认定责任"));
    }
    return groups;
  }

  private static double round(double v, int scale) {
    return BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
  }

  // ---------------------------------------------------------------- 聚合器

  private static final class Aggregator {
    final Map<String, BatchStats> byBatch = new LinkedHashMap<>();
    final Map<String, Set<String>> negatives = new LinkedHashMap<>();
    final List<TraceResult.Unresolved> unresolved = new ArrayList<>();

    BatchStats stats(String rawBatchId) {
      return byBatch.computeIfAbsent(rawBatchId, BatchStats::new);
    }

    void markPositive(String rawBatchId) {
      stats(rawBatchId).hadPositivePath = true;
    }

    void addPrune(String rawBatchId, String reason, String note) {
      BatchStats st = stats(rawBatchId);
      st.pruneReasons.add(reason);
      st.pruneNotes.add(note);
    }

    void addOkPath(String rawBatchId, String sampleKey, String productBatchId,
        boolean missingRecord, Double sim, boolean boundaryMissing) {
      BatchStats st = stats(rawBatchId);
      st.supportSamples.add(sampleKey);
      st.okPaths.add(sampleKey + "->" + productBatchId);
      st.evidence.add(new TraceResult.Evidence(sampleKey, productBatchId, rawBatchId,
          missingRecord ? "EXPANDED_MISSING_RECORD" : "DIRECT_USAGE", missingRecord,
          sim == null ? -1.0 : round(sim, 4)));
      if (sim != null) {
        st.simSum += sim;
        st.simCount++;
      }
      st.posSims.add(sim == null ? 0.5 : sim);
      if (missingRecord) {
        st.missingRecordPaths++;
        st.expandedBy.add("投料记录缺失，按同辅料展开（样品 " + sampleKey + "）");
      }
      if (boundaryMissing) {
        st.boundaryMissing = true;
      }
    }

    List<BatchStats> sorted() {
      List<BatchStats> list = new ArrayList<>(byBatch.values());
      list.sort(Comparator.comparing(BatchStats::rawBatchId));
      return list;
    }
  }

  private static final class BatchStats {
    final String rawBatchId;
    boolean hadPositivePath;
    final Set<String> okSamples = new LinkedHashSet<>();
    final Set<String> supportSamples = new LinkedHashSet<>();
    final List<String> okPaths = new ArrayList<>();
    final Set<String> pruneReasons = new LinkedHashSet<>();
    final List<String> pruneNotes = new ArrayList<>();
    final List<TraceResult.Evidence> evidence = new ArrayList<>();
    final List<Double> posSims = new ArrayList<>();
    final List<String> expandedBy = new ArrayList<>();
    final List<String> contradictedNotes = new ArrayList<>();
    int missingRecordPaths;
    boolean boundaryMissing;
    double simSum;
    int simCount;
    int negativeCount;

    BatchStats(String rawBatchId) {
      this.rawBatchId = rawBatchId;
    }

    String rawBatchId() {
      return rawBatchId;
    }
  }
}
