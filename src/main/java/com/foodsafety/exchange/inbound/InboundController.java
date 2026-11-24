package com.foodsafety.exchange.inbound;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 入站接口：接收容器（CSV/XML 正文 + 签名 + 附件清单 + 发送批次）。 */
@RestController
@RequestMapping("/api/inbound")
@Tag(name = "入站", description = "容器接收：不可变入站库 + 按容器固定规范解析 + 修订状态机")
public class InboundController {

  private final InboundService inboundService;
  private final InboundEnvelopeRepository envelopeRepo;

  public InboundController(InboundService inboundService, InboundEnvelopeRepository envelopeRepo) {
    this.inboundService = inboundService;
    this.envelopeRepo = envelopeRepo;
  }

  @PostMapping(value = "/containers", consumes = "multipart/form-data")
  @Operation(summary = "接收入站容器", description = "按批次固定规范版本解析；签名无效或结构不符记解析失败；"
      + "相同容器号+相同摘要为重传；相同容器号不同摘要返回 409")
  public ResponseEntity<InboundService.IngestResult> ingest(
      @RequestParam String containerId,
      @RequestParam String batchId,
      @RequestParam String sender,
      @RequestParam String signature,
      @RequestParam(required = false) String attachments,
      @RequestPart("file") MultipartFile file) throws IOException {
    InboundService.IngestResult result = inboundService.ingest(
        containerId, batchId, sender, signature, attachments, file.getBytes());
    if (result.conflict()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
    return ResponseEntity.ok(result);
  }

  @GetMapping("/containers/{containerId}")
  @Operation(summary = "查询入站信封", description = "不可变入站库中的字节摘要、签名、发送方、规范版本与接收时间")
  public Map<String, Object> envelope(@PathVariable String containerId) {
    InboundEnvelope envelope = envelopeRepo.findFirstByContainerIdOrderById(containerId)
        .orElseThrow(() -> com.foodsafety.exchange.error.ApiException.notFound(
            "UNKNOWN_CONTAINER", "容器不存在: " + containerId));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("containerId", envelope.getContainerId());
    view.put("batchId", envelope.getBatchId());
    view.put("sender", envelope.getSender());
    view.put("specVersion", envelope.getSpecVersion());
    view.put("payloadSha256", envelope.getPayloadSha256());
    view.put("signature", envelope.getSignature());
    view.put("signatureValid", envelope.isSignatureValid());
    view.put("attachments", envelope.getAttachments());
    view.put("receivedAt", envelope.getReceivedAt());
    view.put("immutable", true);
    return view;
  }
}
