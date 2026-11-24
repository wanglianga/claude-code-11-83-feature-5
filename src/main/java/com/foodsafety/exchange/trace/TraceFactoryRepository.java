package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceFactoryRepository extends JpaRepository<TraceFactory, String> {

  List<TraceFactory> findAllByOrderByFactoryId();
}
