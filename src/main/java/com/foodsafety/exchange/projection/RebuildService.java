package com.foodsafety.exchange.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.correlation.ReportChainState;
import com.foodsafety.exchange.correlation.RevisionReducer;
import com.foodsafety.exchange.delivery.OperationLedger;
import com.foodsafety.exchange.delivery.OperationLedgerRepository;
import com.foodsafety.exchange.delivery.OperationReducer;
import com.foodsafety.exchange.event.BusinessEvent;
import com.foodsafety.exchange.event.BusinessEventRepository;
import com.foodsafety.exchange.event.EventKeys;
import com.foodsafety.exchange.event.EventLedger;
import com.foodsafety.exchange.event.EventType;
import com.foodsafety.exchange.inbound.BatchRegistry;
import com.foodsafety.exchange.inbound.BatchRegistryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投影重建：清空版本化投影后按事件账全量重放，
 * 并核验：1) 重建前后投影差异；2) 操作账与事件折叠一致；3) 规范换版失效边界。
 */
@Service
public class RebuildService {

  private static final ObjectMapper OM = new ObjectMapper();

  private final BusinessEventRepository eventRepo;
  private final ReportRevisionProjectionRepository revisionRepo;
  private final ReportHeadProjectionRepository headRepo;
  private final OperationLedgerRepository ledgerRepo;
  private final BatchRegistryRepository batchRepo;
  private final Projector projector;
  private final EventLedger eventLedger;

  public RebuildService(BusinessEventRepository eventRepo,
      ReportRevisionProjectionRepository revisionRepo, ReportHeadProjectionRepository headRepo,
      OperationLedgerRepository ledgerRepo, BatchRegistryRepository batchRepo,
      Projector projector, EventLedger eventLedger) {
    this.eventRepo = eventRepo;
    this.revisionRepo = revisionRepo;
    this.headRepo = headRepo;
    this.ledgerRepo = ledgerRepo;
    this.batchRepo = batchRepo;
    this.projector = projector;
    this.eventLedger = eventLedger;
  }

  public record RebuildReport(int rebuiltPartitions, long totalEvents,
      Map<String, Long> partitionVersions, List<String> projectionDifferences,
      List<String> operationMismatches, List<String> specBoundaryViolations,
      boolean consistent, long rebuildEventSeq) {}

  @Transactional
  public RebuildReport rebuild() {
    Map<String, String> before = snapshotProjections();
    long totalEvents = eventRepo.count();

    // 1) 清空投影，按事件账全量重放（重放窗口 = 全量事件序列）
    revisionRepo.deleteAll();
    headRepo.deleteAll();
    List<String> reportNos = eventRepo.distinctReportNos();
    Map<String, Long> partitionVersions = new TreeMap<>();
    for (String reportNo : reportNos) {
      ReportChainState state = new ReportChainState();
      for (BusinessEvent event : eventRepo.findByReportNoOrderByEventSeq(reportNo)) {
        RevisionReducer.apply(state, event);
      }
      projector.persistState(reportNo, state);
      partitionVersions.put(reportNo, state.partitionVersion);
    }

    // 2) 重建前后差异（投影漂移检测）
    Map<String, String> after = snapshotProjections();
    List<String> differences = diff(before, after);

    // 3) 操作账与事件折叠一致性
    List<String> operationMismatches = checkOperations();

    // 4) 规范换版失效边界：容器固定的规范版本须与批次注册一致
    List<String> specViolations = checkSpecBoundaries();

    boolean consistent = differences.isEmpty() && operationMismatches.isEmpty()
        && specViolations.isEmpty();
    var event = eventLedger.append(EventType.PROJECTION_REBUILT, EventKeys.none(), Map.of(
        "partitions", reportNos.size(),
        "differences", differences.size(),
        "operationMismatches", operationMismatches.size(),
        "specBoundaryViolations", specViolations.size()));
    return new RebuildReport(reportNos.size(), totalEvents, partitionVersions, differences,
        operationMismatches, specViolations, consistent, event.getEventSeq());
  }

  private Map<String, String> snapshotProjections() {
    Map<String, String> snapshot = new TreeMap<>();
    for (ReportRevisionProjection row : revisionRepo.findAll()) {
      snapshot.put("R:" + row.getReportNo() + "#" + row.getRevisionNo(),
          row.getStatus() + "|" + row.getDigest() + "|" + row.getSpecVersion()
              + "|late=" + row.isLateArrival() + "|seq=" + row.getAppliedEventSeq());
    }
    for (ReportHeadProjection head : headRepo.findAll()) {
      snapshot.put("H:" + head.getReportNo(),
          "head=" + head.getHeadRevision() + "|" + head.getHeadStatus()
              + "|v=" + head.getPartitionVersion() + "|seq=" + head.getLastEventSeq());
    }
    return snapshot;
  }

  private List<String> diff(Map<String, String> before, Map<String, String> after) {
    List<String> differences = new ArrayList<>();
    Map<String, String> union = new TreeMap<>(before);
    after.forEach(union::putIfAbsent);
    for (Map.Entry<String, String> entry : union.entrySet()) {
      String oldValue = before.get(entry.getKey());
      String newValue = after.get(entry.getKey());
      if (oldValue == null) {
        differences.add(entry.getKey() + ": 重建前缺失 -> " + newValue);
      } else if (newValue == null) {
        differences.add(entry.getKey() + ": 重建后缺失（原为 " + oldValue + "）");
      } else if (!oldValue.equals(newValue)) {
        differences.add(entry.getKey() + ": " + oldValue + " -> " + newValue);
      }
    }
    return differences;
  }

  private List<String> checkOperations() {
    List<String> mismatches = new ArrayList<>();
    for (String operationNo : eventRepo.distinctOperationNos()) {
      OperationReducer.OperationState folded = OperationReducer.fold(operationNo,
          eventRepo.findByOperationNoOrderByEventSeq(operationNo));
      OperationLedger ledger = ledgerRepo.findById(operationNo).orElse(null);
      if (ledger == null) {
        mismatches.add(operationNo + ": 事件账存在但操作账缺失");
        continue;
      }
      if (folded.status != null && !folded.status.name().equals(ledger.getStatus())) {
        mismatches.add(operationNo + ": 状态不一致 事件折叠=" + folded.status
            + " 操作账=" + ledger.getStatus());
      }
    }
    return mismatches;
  }

  private List<String> checkSpecBoundaries() {
    List<String> violations = new ArrayList<>();
    for (BusinessEvent event : eventRepo.findAllByOrderByEventSeq()) {
      if (!EventType.INBOUND_RECEIVED.name().equals(event.getEventType())) {
        continue;
      }
      try {
        JsonNode payload = OM.readTree(event.getPayload());
        String batchId = payload.path("batchId").asText();
        String specVersion = payload.path("specVersion").asText();
        BatchRegistry batch = batchRepo.findById(batchId).orElse(null);
        if (batch == null) {
          violations.add(event.getContainerId() + ": 批次 " + batchId + " 未注册");
        } else if (!batch.getSpecVersion().equals(specVersion)) {
          violations.add(event.getContainerId() + ": 容器固定规范 " + specVersion
              + " 与批次 " + batchId + " 注册规范 " + batch.getSpecVersion()
              + " 不一致（规范换版失效边界）");
        } else if (event.getOccurredAt().isBefore(batch.getEffectiveFrom())) {
          violations.add(event.getContainerId() + ": 容器到达早于批次 " + batchId + " 生效时间");
        }
      } catch (Exception e) {
        violations.add(event.getContainerId() + ": 事件载荷无法解析");
      }
    }
    return violations;
  }
}
