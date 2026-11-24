package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceRawBatchRepository extends JpaRepository<TraceRawBatch, String> {

  List<TraceRawBatch> findAllByOrderByRawBatchId();
}
