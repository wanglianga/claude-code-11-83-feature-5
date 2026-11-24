package com.foodsafety.exchange.trace;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三工厂样例数据：三家工厂共用同一个辅料供应商（SUP-SHARED）。
 * 只有两家（F1/F2）在污染可能到达的时间内使用了同一原料批次 RB-STARCH-OK；
 * F2 另有一条到货晚于投料的批次 RB-STARCH-LATE（应被时间剪枝），
 * F3 使用相同辅料但批次 RB-STARCH-OTHER 的指纹与污染谱不符（应被指纹剪枝）。
 * F2 还有一条未登记原料批次的辅料投料记录（缺失批次记录，导致候选范围扩张）。
 *
 * <p>这些数据仅用于演示候选推导，不代表任何真实企业或责任结论。</p>
 */
@Service
public class TraceDemoService {

  private final JdbcTemplate jdbc;
  private final TraceFactoryRepository factoryRepo;
  private final TraceLineRepository lineRepo;
  private final TraceProductBatchRepository productBatchRepo;
  private final TraceRawBatchRepository rawBatchRepo;
  private final TraceUsageRepository usageRepo;
  private final TraceSampleRepository sampleRepo;
  private final TraceSpectrumRepository spectrumRepo;

  public TraceDemoService(JdbcTemplate jdbc, TraceFactoryRepository factoryRepo,
      TraceLineRepository lineRepo, TraceProductBatchRepository productBatchRepo,
      TraceRawBatchRepository rawBatchRepo, TraceUsageRepository usageRepo,
      TraceSampleRepository sampleRepo, TraceSpectrumRepository spectrumRepo) {
    this.jdbc = jdbc;
    this.factoryRepo = factoryRepo;
    this.lineRepo = lineRepo;
    this.productBatchRepo = productBatchRepo;
    this.rawBatchRepo = rawBatchRepo;
    this.usageRepo = usageRepo;
    this.sampleRepo = sampleRepo;
    this.spectrumRepo = spectrumRepo;
  }

  @Transactional
  public void reset() {
    jdbc.execute("TRUNCATE trace_version, trace_spectrum, trace_sample, trace_usage, "
        + "trace_raw_batch, trace_product_batch, trace_line, trace_factory CASCADE");
  }

  @Transactional
  public void seed() {
    reset();
    factoryRepo.save(new TraceFactory("F1", "第一工厂", "华东"));
    factoryRepo.save(new TraceFactory("F2", "第二工厂", "华东"));
    factoryRepo.save(new TraceFactory("F3", "第三工厂", "华南"));

    lineRepo.save(new TraceLine("L1", "F1", "一号线"));
    lineRepo.save(new TraceLine("L2", "F2", "二号线"));
    lineRepo.save(new TraceLine("L3", "F3", "三号线"));

    productBatchRepo.save(new TraceProductBatch("PB1", "L1", "CEREAL-BAR",
        at("2026-03-10T08:00:00Z"), at("2026-03-10T16:00:00Z")));
    productBatchRepo.save(new TraceProductBatch("PB2", "L2", "CEREAL-BAR",
        at("2026-03-10T09:00:00Z"), at("2026-03-10T17:00:00Z")));
    productBatchRepo.save(new TraceProductBatch("PB3", "L3", "CEREAL-BAR",
        at("2026-03-11T08:00:00Z"), at("2026-03-11T16:00:00Z")));

    String fpA = "{\"pb206\":1.00,\"pb207\":0.82,\"pb208\":0.64,\"as75\":0.10}";
    String fpB = "{\"pb206\":0.20,\"pb207\":0.95,\"pb208\":0.30,\"cd111\":0.80}";
    String fpEm1 = "{\"pb206\":0.98,\"pb207\":0.81,\"pb208\":0.66,\"as75\":0.09}";
    String fpEm2 = "{\"pb206\":0.90,\"pb207\":0.70,\"pb208\":0.50,\"as75\":0.30,\"zn66\":0.50}";

    rawBatchRepo.save(new TraceRawBatch("RB-STARCH-OK", "STARCH", "SUP-SHARED",
        at("2026-03-01T00:00:00Z"), at("2026-03-04T00:00:00Z"),
        at("2026-03-05T00:00:00Z"), at("2026-03-06T00:00:00Z"), fpA));
    // 同一供应商的晚到批次：到货 2026-03-13 晚于 PB2 生产结束 2026-03-10T17:00Z -> 时间不可达
    rawBatchRepo.save(new TraceRawBatch("RB-STARCH-LATE", "STARCH", "SUP-SHARED",
        at("2026-03-12T00:00:00Z"), at("2026-03-12T00:00:00Z"),
        at("2026-03-13T00:00:00Z"), at("2026-03-13T00:00:00Z"), fpA));
    // 第三工厂使用相同辅料，但指纹谱型与污染不符
    rawBatchRepo.save(new TraceRawBatch("RB-STARCH-OTHER", "STARCH", "SUP-SHARED",
        at("2026-03-04T00:00:00Z"), at("2026-03-05T00:00:00Z"),
        at("2026-03-06T00:00:00Z"), at("2026-03-06T00:00:00Z"), fpB));

    rawBatchRepo.save(new TraceRawBatch("RB-EM-01", "EMULSIFIER", "SUP-EM1",
        at("2026-02-28T00:00:00Z"), at("2026-03-01T00:00:00Z"),
        at("2026-03-02T00:00:00Z"), at("2026-03-02T00:00:00Z"), fpEm1));
    rawBatchRepo.save(new TraceRawBatch("RB-EM-02", "EMULSIFIER", "SUP-EM2",
        at("2026-03-01T00:00:00Z"), at("2026-03-02T00:00:00Z"),
        at("2026-03-03T00:00:00Z"), at("2026-03-03T00:00:00Z"), fpEm2));

    usageRepo.save(new TraceUsage("PB1", "RB-STARCH-OK", "STARCH",
        at("2026-03-10T09:00:00Z"), at("2026-03-10T11:00:00Z"), bd("50")));
    usageRepo.save(new TraceUsage("PB2", "RB-STARCH-OK", "STARCH",
        at("2026-03-10T10:00:00Z"), at("2026-03-10T12:00:00Z"), bd("50")));
    usageRepo.save(new TraceUsage("PB2", "RB-STARCH-LATE", "STARCH",
        at("2026-03-10T11:00:00Z"), at("2026-03-10T13:00:00Z"), bd("20")));
    // 未登记原料批次的辅料投料记录：候选范围扩张来源
    usageRepo.save(new TraceUsage("PB2", null, "EMULSIFIER",
        at("2026-03-10T13:00:00Z"), at("2026-03-10T14:00:00Z"), bd("5")));
    usageRepo.save(new TraceUsage("PB3", "RB-STARCH-OTHER", "STARCH",
        at("2026-03-11T09:00:00Z"), at("2026-03-11T11:00:00Z"), bd("50")));

    sampleRepo.save(new TraceSample("S-PB1", "PB1", at("2026-03-12T08:00:00Z"), "F1 成品样"));
    sampleRepo.save(new TraceSample("S-PB1-NEG", "PB1", at("2026-03-12T08:30:00Z"), "F1 平行阴性样"));
    sampleRepo.save(new TraceSample("S-PB2", "PB2", at("2026-03-12T09:00:00Z"), "F2 成品样"));
    sampleRepo.save(new TraceSample("S-PB3", "PB3", at("2026-03-13T08:00:00Z"), "F3 成品样"));

    spectrumRepo.save(new TraceSpectrum("SP-1", "S-PB1", "LEAD", fpA, bd("0.005"), "mg/kg",
        bd("0.08"), bd("0.42"), "POSITIVE", "LAB-A", at("2026-03-20T10:00:00Z")));
    spectrumRepo.save(new TraceSpectrum("SP-1N", "S-PB1-NEG", "LEAD", "{}", bd("0.005"), "mg/kg",
        bd("0.08"), bd("0.001"), "NEGATIVE", "LAB-A", at("2026-03-20T10:05:00Z")));
    spectrumRepo.save(new TraceSpectrum("SP-2", "S-PB2", "LEAD",
        "{\"pb206\":1.00,\"pb207\":0.80,\"pb208\":0.62,\"as75\":0.12}",
        bd("0.005"), "mg/kg", bd("0.08"), bd("0.39"), "POSITIVE", "LAB-A",
        at("2026-03-20T10:10:00Z")));
    spectrumRepo.save(new TraceSpectrum("SP-3", "S-PB3", "LEAD",
        "{\"pb206\":1.00,\"pb207\":0.83,\"pb208\":0.63,\"as75\":0.11}",
        bd("0.005"), "mg/kg", bd("0.10"), bd("0.55"), "POSITIVE", "LAB-B",
        at("2026-03-21T09:00:00Z")));
  }

  public List<String> sampleKeys() {
    return sampleRepo.findAllByOrderBySampleKey().stream().map(TraceSample::getSampleKey).toList();
  }

  /**
   * 模拟补登缺失的批次记录：把未登记原料批次的辅料投料记录补登为指定批次。
   * 用于演示“缺失批次记录造成的候选范围扩张”在记录补登后如何收敛。
   */
  @Transactional
  public int backfillMissingUsage(String rawBatchId) {
    TraceRawBatch raw = rawBatchRepo.findById(rawBatchId)
        .orElseThrow(() -> new IllegalArgumentException("原料批次不存在: " + rawBatchId));
    List<TraceUsage> missing = usageRepo.findByRawBatchIdIsNull();
    int updated = 0;
    for (TraceUsage usage : missing) {
      if (usage.getIngredientCode().equals(raw.getIngredientCode())) {
        updated += jdbc.update("UPDATE trace_usage SET raw_batch_id = ? WHERE usage_id = ?",
            rawBatchId, usage.getUsageId());
      }
    }
    return updated;
  }

  public List<String> missingUsageIngredients() {
    return usageRepo.findByRawBatchIdIsNull().stream()
        .map(TraceUsage::getIngredientCode).distinct().sorted().toList();
  }

  private static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
