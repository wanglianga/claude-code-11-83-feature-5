package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceProductBatchRepository extends JpaRepository<TraceProductBatch, String> {

  List<TraceProductBatch> findAllByOrderByProductBatchId();
}
