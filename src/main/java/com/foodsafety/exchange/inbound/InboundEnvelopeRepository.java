package com.foodsafety.exchange.inbound;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEnvelopeRepository extends JpaRepository<InboundEnvelope, Long> {

  Optional<InboundEnvelope> findFirstByContainerIdOrderById(String containerId);

  Optional<InboundEnvelope> findByContainerIdAndPayloadSha256(String containerId, String payloadSha256);
}
