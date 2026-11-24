package com.foodsafety.exchange.replay;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 事件重放接口：按查询时点重放报告链与操作状态。 */
@RestController
@RequestMapping("/api/replay")
@Tag(name = "事件重放", description = "按事件序列号或时间点重放报告链与操作状态（扫描事件账，管理重放窗口）")
public class ReplayController {

  private final ReplayService replayService;

  public ReplayController(ReplayService replayService) {
    this.replayService = replayService;
  }

  @GetMapping("/reports/{reportNo}")
  @Operation(summary = "重放报告链", description = "asOfSeq 或 asOfTime 指定时点；缺省为当前时点")
  public ReplayService.ReportChainView replayReport(
      @PathVariable String reportNo,
      @RequestParam(required = false) Long asOfSeq,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
      OffsetDateTime asOfTime) {
    return replayService.replayReport(reportNo, asOfSeq, asOfTime);
  }

  @GetMapping("/operations/{operationNo}")
  @Operation(summary = "重放操作状态", description = "按事件序列重放操作号状态（含未知->已确认转移）")
  public ReplayService.OperationReplayView replayOperation(
      @PathVariable String operationNo,
      @RequestParam(required = false) Long asOfSeq,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
      OffsetDateTime asOfTime) {
    return replayService.replayOperation(operationNo, asOfSeq, asOfTime);
  }

  @GetMapping("/reports/{reportNo}/verify")
  @Operation(summary = "核验重放与投影一致", description = "同一事件序列上，事件重放与版本化投影应给出相同当前报告")
  public ReplayService.VerifyView verify(@PathVariable String reportNo) {
    return replayService.verifyReport(reportNo);
  }
}
