package com.foodsafety.exchange.reconcile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对账接口：容器、样品、报告修订、操作号与回执之间的证据链。 */
@RestController
@RequestMapping("/api/reconciliation")
@Tag(name = "对账", description = "容器/样品/报告修订/操作号/回执证据链")
public class ReconciliationController {

  private final ReconciliationService reconciliationService;

  public ReconciliationController(ReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @GetMapping("/containers/{containerId}")
  @Operation(summary = "容器证据链")
  public Map<String, Object> container(@PathVariable String containerId) {
    return reconciliationService.containerEvidence(containerId);
  }

  @GetMapping("/reports/{reportNo}")
  @Operation(summary = "报告证据链")
  public Map<String, Object> report(@PathVariable String reportNo) {
    return reconciliationService.reportEvidence(reportNo);
  }

  @GetMapping("/samples/{sampleKey}")
  @Operation(summary = "样品证据链")
  public Map<String, Object> sample(@PathVariable String sampleKey) {
    return reconciliationService.sampleEvidence(sampleKey);
  }

  @GetMapping("/operations/{operationNo}")
  @Operation(summary = "操作号证据链")
  public Map<String, Object> operation(@PathVariable String operationNo) {
    return reconciliationService.operationEvidence(operationNo);
  }
}
