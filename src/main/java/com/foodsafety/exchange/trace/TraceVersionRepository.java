package com.foodsafety.exchange.trace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceVersionRepository extends JpaRepository<TraceVersion, String> {

  Optional<TraceVersion> findByCaseIdAndVersionId(String caseId, String versionId);

  List<TraceVersion> findByCaseIdOrderByCreatedAtAsc(String caseId);

  List<TraceVersion> findAllByOrderByCreatedAtAsc();
}
