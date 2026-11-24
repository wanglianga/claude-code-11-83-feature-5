package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceUsageRepository extends JpaRepository<TraceUsage, Long> {

  List<TraceUsage> findByProductBatchIdOrderByUsageId(String productBatchId);

  List<TraceUsage> findByRawBatchIdIsNull();

  List<TraceUsage> findAllByOrderByUsageId();
}
