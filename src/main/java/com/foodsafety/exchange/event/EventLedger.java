package com.foodsafety.exchange.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsafety.exchange.projection.Projector;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件账追加器：所有状态变化先写入不可变事件账；
 * 影响报告链投影的事件在同一事务内同步推进版本化投影。
 */
@Service
public class EventLedger {

  /** 会影响报告修订投影的事件类型。 */
  private static final Set<EventType> REPORT_PROJECTED = Set.of(
      EventType.REPORT_ACCEPTED,
      EventType.REPORT_SUPERSEDED,
      EventType.BUSINESS_REJECTED,
      EventType.QUARANTINED,
      EventType.LATE_REVISION_IGNORED);

  private final BusinessEventRepository repository;
  private final Projector projector;
  private final ObjectMapper objectMapper;

  public EventLedger(BusinessEventRepository repository, Projector projector, ObjectMapper objectMapper) {
    this.repository = repository;
    this.projector = projector;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public BusinessEvent append(EventType type, EventKeys keys, Map<String, Object> payload) {
    BusinessEvent event = new BusinessEvent(type, keys, toJson(payload), OffsetDateTime.now());
    BusinessEvent saved = repository.save(event);
    // 提交前需要拿到序列号用于投影
    repository.flush();
    if (REPORT_PROJECTED.contains(type)) {
      projector.apply(saved);
    }
    return saved;
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalArgumentException("事件载荷无法序列化: " + e.getMessage(), e);
    }
  }
}
