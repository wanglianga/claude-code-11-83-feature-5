package com.foodsafety.exchange.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 追溯快照的 JSON 编解码：生产图与污染谱分别序列化，便于分别计算摘要并冻结到追溯版本。
 * 反序列化后可完全复现求值输入。
 */
@Component
public class TraceCodec {

  private final ObjectMapper mapper;

  public TraceCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 生产图（不含实验结果）：工厂/生产线/成品批次/原料批次/投料/样品。 */
  public ObjectNode graph(TraceSnapshot snapshot) {
    ObjectNode node = mapper.createObjectNode();
    node.set("factories", mapper.valueToTree(snapshot.factories()));
    node.set("lines", mapper.valueToTree(snapshot.lines()));
    node.set("productBatches", mapper.valueToTree(snapshot.productBatches()));
    node.set("rawBatches", mapper.valueToTree(snapshot.rawBatches()));
    node.set("usages", mapper.valueToTree(snapshot.usages()));
    node.set("samples", mapper.valueToTree(snapshot.samples()));
    return node;
  }

  /** 实验结果（污染谱）。 */
  public ArrayNode spectra(TraceSnapshot snapshot) {
    return mapper.valueToTree(snapshot.spectra());
  }

  public JsonNode node(Object value) {
    return mapper.valueToTree(value);
  }

  public TraceSnapshot snapshot(JsonNode graph, JsonNode spectra) {
    GraphHolder holder = mapper.convertValue(graph, GraphHolder.class);
    List<TraceSnapshot.SpectrumRec> spectraList = spectra == null || spectra.isNull()
        ? List.of()
        : mapper.convertValue(spectra,
            new TypeReference<List<TraceSnapshot.SpectrumRec>>() {});
    return new TraceSnapshot(holder.factories(), holder.lines(), holder.productBatches(),
        holder.rawBatches(), holder.usages(), holder.samples(), spectraList);
  }

  private record GraphHolder(
      List<TraceSnapshot.FactoryRec> factories,
      List<TraceSnapshot.LineRec> lines,
      List<TraceSnapshot.ProductBatchRec> productBatches,
      List<TraceSnapshot.RawBatchRec> rawBatches,
      List<TraceSnapshot.UsageRec> usages,
      List<TraceSnapshot.SampleRec> samples) {}
}
