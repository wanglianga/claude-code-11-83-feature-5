package com.foodsafety.exchange.trace;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追溯求值使用的中性快照：生产图（工厂/生产线/成品批次/原料批次/投料/样品）与污染谱。
 * 既可从数据库实体构建，也可从追溯版本冻结的 JSON 还原，从而保证求值可复现。
 */
public record TraceSnapshot(
    List<FactoryRec> factories,
    List<LineRec> lines,
    List<ProductBatchRec> productBatches,
    List<RawBatchRec> rawBatches,
    List<UsageRec> usages,
    List<SampleRec> samples,
    List<SpectrumRec> spectra) {

  public record FactoryRec(String factoryId, String name, String region) {}

  public record LineRec(String lineId, String factoryId, String name) {}

  public record ProductBatchRec(String productBatchId, String lineId, String productCode,
      OffsetDateTime productionStart, OffsetDateTime productionEnd) {}

  public record RawBatchRec(String rawBatchId, String ingredientCode, String supplierId,
      OffsetDateTime purchaseFrom, OffsetDateTime purchaseTo, OffsetDateTime arrivalFrom,
      OffsetDateTime arrivalTo, Map<String, Double> fingerprint) {}

  public record UsageRec(String productBatchId, String rawBatchId, String ingredientCode,
      OffsetDateTime usedFrom, OffsetDateTime usedTo, BigDecimal quantityKg) {}

  public record SampleRec(String sampleKey, String productBatchId, OffsetDateTime sampledAt,
      String note) {}

  public record SpectrumRec(String spectrumId, String sampleKey, String analyte,
      Map<String, Double> fingerprint, BigDecimal detectionLimit, String detectionLimitUnit,
      BigDecimal relativeUncertainty, BigDecimal resultValue, String polarity, String lab,
      OffsetDateTime measuredAt) {}

  public Map<String, FactoryRec> factoryIndex() {
    Map<String, FactoryRec> map = new LinkedHashMap<>();
    factories.forEach(f -> map.put(f.factoryId(), f));
    return map;
  }

  public Map<String, LineRec> lineIndex() {
    Map<String, LineRec> map = new LinkedHashMap<>();
    lines.forEach(l -> map.put(l.lineId(), l));
    return map;
  }

  public Map<String, ProductBatchRec> productBatchIndex() {
    Map<String, ProductBatchRec> map = new LinkedHashMap<>();
    productBatches.forEach(p -> map.put(p.productBatchId(), p));
    return map;
  }

  public Map<String, RawBatchRec> rawBatchIndex() {
    Map<String, RawBatchRec> map = new LinkedHashMap<>();
    rawBatches.forEach(r -> map.put(r.rawBatchId(), r));
    return map;
  }

  public Map<String, SampleRec> sampleIndex() {
    Map<String, SampleRec> map = new LinkedHashMap<>();
    samples.forEach(s -> map.put(s.sampleKey(), s));
    return map;
  }

  public Map<String, List<UsageRec>> usagesByProductBatch() {
    Map<String, List<UsageRec>> map = new LinkedHashMap<>();
    for (UsageRec usage : usages) {
      map.computeIfAbsent(usage.productBatchId(), k -> new ArrayList<>()).add(usage);
    }
    return map;
  }

  public List<SpectrumRec> spectraForAnalyte(String analyte) {
    return spectra.stream().filter(s -> s.analyte().equals(analyte)).toList();
  }

  public TraceSnapshot withSpectra(List<SpectrumRec> newSpectra) {
    return new TraceSnapshot(factories, lines, productBatches, rawBatches, usages, samples, newSpectra);
  }
}
