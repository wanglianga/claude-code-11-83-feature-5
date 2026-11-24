package com.foodsafety.exchange.projection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 版本化当前投影读取与重建。 */
@RestController
@RequestMapping("/api/projections")
@Tag(name = "版本化投影", description = "按报告号/样品键分区的版本化当前投影；常用对账读取快路径")
public class ProjectionController {

  private final ReportRevisionProjectionRepository revisionRepo;
  private final ReportHeadProjectionRepository headRepo;
  private final RebuildService rebuildService;

  public ProjectionController(ReportRevisionProjectionRepository revisionRepo,
      ReportHeadProjectionRepository headRepo, RebuildService rebuildService) {
    this.revisionRepo = revisionRepo;
    this.headRepo = headRepo;
    this.rebuildService = rebuildService;
  }

  @GetMapping("/reports/{reportNo}")
  @Operation(summary = "读取报告投影", description = "链头 + 全部修订的当前投影（快路径）")
  public Map<String, Object> report(@PathVariable String reportNo) {
    Map<String, Object> view = new LinkedHashMap<>();
    headRepo.findById(reportNo).ifPresentOrElse(head -> {
      view.put("reportNo", head.getReportNo());
      view.put("sampleKey", head.getSampleKey());
      view.put("head", head.getHeadRevision());
      view.put("headStatus", head.getHeadStatus());
      view.put("partitionVersion", head.getPartitionVersion());
      view.put("lastEventSeq", head.getLastEventSeq());
    }, () -> {
      view.put("reportNo", reportNo);
      view.put("head", null);
    });
    view.put("revisions", revisionRepo.findByReportNoOrderByRevisionNo(reportNo).stream()
        .map(ProjectionController::revisionView).toList());
    return view;
  }

  @GetMapping("/samples/{sampleKey}")
  @Operation(summary = "按样品键读取投影", description = "样品键分区的报告链头与修订")
  public Map<String, Object> sample(@PathVariable String sampleKey) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sampleKey", sampleKey);
    view.put("heads", headRepo.findBySampleKeyOrderByReportNo(sampleKey).stream()
        .map(head -> {
          Map<String, Object> h = new LinkedHashMap<String, Object>();
          h.put("reportNo", head.getReportNo());
          h.put("head", head.getHeadRevision());
          h.put("headStatus", head.getHeadStatus());
          h.put("partitionVersion", head.getPartitionVersion());
          return h;
        }).toList());
    view.put("revisions", revisionRepo.findBySampleKeyOrderByReportNo(sampleKey).stream()
        .map(ProjectionController::revisionView).toList());
    return view;
  }

  @PostMapping("/rebuild")
  @Operation(summary = "重建投影", description = "按事件账全量重放重建投影，报告重建差异、操作账一致性与规范换版失效边界")
  public RebuildService.RebuildReport rebuild() {
    return rebuildService.rebuild();
  }

  public static Map<String, Object> revisionView(ReportRevisionProjection row) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reportNo", row.getReportNo());
    view.put("revisionNo", row.getRevisionNo());
    view.put("sampleKey", row.getSampleKey());
    view.put("status", row.getStatus());
    view.put("digest", row.getDigest());
    view.put("specVersion", row.getSpecVersion());
    view.put("supersedes", row.getSupersedes());
    view.put("containerId", row.getContainerId());
    view.put("lateArrival", row.isLateArrival());
    view.put("reason", row.getReason());
    view.put("appliedEventSeq", row.getAppliedEventSeq());
    return view;
  }
}
