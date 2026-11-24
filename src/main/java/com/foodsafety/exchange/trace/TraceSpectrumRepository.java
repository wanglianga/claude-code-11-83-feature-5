package com.foodsafety.exchange.trace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceSpectrumRepository extends JpaRepository<TraceSpectrum, String> {

  List<TraceSpectrum> findBySampleKeyOrderBySpectrumId(String sampleKey);

  List<TraceSpectrum> findByAnalyteOrderBySampleKeyAscMeasuredAtAsc(String analyte);

  List<TraceSpectrum> findAllByOrderBySampleKeyAscMeasuredAtAsc();
}
