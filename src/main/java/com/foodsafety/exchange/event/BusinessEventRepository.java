package com.foodsafety.exchange.event;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessEventRepository extends JpaRepository<BusinessEvent, Long> {

  List<BusinessEvent> findByReportNoAndEventSeqLessThanEqualOrderByEventSeq(String reportNo, long seq);

  List<BusinessEvent> findByReportNoOrderByEventSeq(String reportNo);

  List<BusinessEvent> findByOperationNoAndEventSeqLessThanEqualOrderByEventSeq(String operationNo, long seq);

  List<BusinessEvent> findByOperationNoOrderByEventSeq(String operationNo);

  List<BusinessEvent> findByContainerIdOrderByEventSeq(String containerId);

  List<BusinessEvent> findBySampleKeyOrderByEventSeq(String sampleKey);

  List<BusinessEvent> findAllByOrderByEventSeq();

  @Query("select distinct e.reportNo from BusinessEvent e where e.reportNo is not null")
  List<String> distinctReportNos();

  @Query("select distinct e.operationNo from BusinessEvent e where e.operationNo is not null")
  List<String> distinctOperationNos();

  @Query("select max(e.eventSeq) from BusinessEvent e where e.occurredAt <= :asOf")
  Optional<Long> maxSeqAt(@Param("asOf") java.time.OffsetDateTime asOf);

  @Query("select max(e.eventSeq) from BusinessEvent e")
  Optional<Long> maxSeq();
}
