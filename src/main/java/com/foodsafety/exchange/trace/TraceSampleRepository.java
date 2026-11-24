package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceSampleRepository extends JpaRepository<TraceSample, String> {

  List<TraceSample> findAllByOrderBySampleKey();
}
