package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceLineRepository extends JpaRepository<TraceLine, String> {

  List<TraceLine> findAllByOrderByLineId();
}
