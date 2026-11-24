package com.foodsafety.exchange.delivery;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {

  List<Receipt> findByOperationNoOrderByIssuedAt(String operationNo);
}
