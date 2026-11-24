package com.foodsafety.exchange.targetsim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetReceiptRepository extends JpaRepository<TargetReceipt, String> {

  List<TargetReceipt> findByOperationNoOrderByIssuedAt(String operationNo);
}
