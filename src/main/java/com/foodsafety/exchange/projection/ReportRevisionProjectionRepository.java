package com.foodsafety.exchange.projection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRevisionProjectionRepository
    extends JpaRepository<ReportRevisionProjection, ReportRevisionProjection.Key> {

  List<ReportRevisionProjection> findByReportNoOrderByRevisionNo(String reportNo);

  List<ReportRevisionProjection> findBySampleKeyOrderByReportNo(String sampleKey);

  Optional<ReportRevisionProjection> findByReportNoAndRevisionNo(String reportNo, Integer revisionNo);
}
