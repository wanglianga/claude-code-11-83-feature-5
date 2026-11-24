package com.foodsafety.exchange.projection;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportHeadProjectionRepository extends JpaRepository<ReportHeadProjection, String> {

  List<ReportHeadProjection> findBySampleKeyOrderByReportNo(String sampleKey);
}
