package com.foodsafety.exchange.meta;

import com.foodsafety.exchange.inbound.BatchRegistryRepository;
import com.foodsafety.exchange.validation.BusinessValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 元信息：规范版本与批次绑定（容器创建时固定的规范由此确定）。 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "元信息", description = "规范版本与发送批次注册表")
public class MetaController {

  private final BatchRegistryRepository batchRepo;

  public MetaController(BatchRegistryRepository batchRepo) {
    this.batchRepo = batchRepo;
  }

  @GetMapping("/spec-versions")
  @Operation(summary = "规范版本注册表", description = "批次 -> 规范版本绑定与单位词表；容器按其创建时固定的规范解析")
  public Map<String, Object> specVersions() {
    Map<String, Object> view = new LinkedHashMap<>();
    List<Map<String, Object>> batches = batchRepo.findAll().stream()
        .sorted(Comparator.comparing(b -> b.getEffectiveFrom()))
        .map(batch -> {
          Map<String, Object> b = new LinkedHashMap<String, Object>();
          b.put("batchId", batch.getBatchId());
          b.put("specVersion", batch.getSpecVersion());
          b.put("effectiveFrom", batch.getEffectiveFrom().toString());
          return b;
        }).toList();
    view.put("batches", batches);
    view.put("specs", List.of(
        Map.of("specVersion", "CSV1", "format", "CSV", "units", BusinessValidator.CSV1_UNITS),
        Map.of("specVersion", "XML2", "format", "XML", "units", BusinessValidator.XML2_UNITS)));
    return view;
  }
}
