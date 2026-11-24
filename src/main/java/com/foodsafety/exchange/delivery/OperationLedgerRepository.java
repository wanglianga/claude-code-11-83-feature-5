package com.foodsafety.exchange.delivery;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLedgerRepository extends JpaRepository<OperationLedger, String> {

  List<OperationLedger> findByReportNoOrderByOperationNo(String reportNo);

  List<OperationLedger> findByReportNoAndRevisionNo(String reportNo, int revisionNo);

  Optional<OperationLedger> findFirstByReportNoAndRevisionNoAndPayloadSha256AndStatusIn(
      String reportNo, int revisionNo, String payloadSha256, List<String> statuses);
}
