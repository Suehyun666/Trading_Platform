package com.hts.account.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;

@ApplicationScoped
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = Logger.getLogger(KafkaEventPublisher.class);

    @Inject
    @Channel("account-events")
    Emitter<byte[]> emitter;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void publish(String eventType, long accountId, BigDecimal amount) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("eventType", eventType);
            event.put("accountId", accountId);
            event.put("amount", amount.toString());
            event.put("timestamp", Instant.now().toString());

            byte[] payload = objectMapper.writeValueAsBytes(event);

            // ✅ 비동기 전송 - fire-and-forget (backpressure 관리 포함)
            // Kafka metadata로 partition key 설정 (같은 accountId는 같은 partition)
            OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(String.valueOf(accountId))
                .build();

            Message<byte[]> message = Message.of(payload)
                .addMetadata(metadata);

            // ✅ 비동기 전송 - fire-and-forget
            try {
                emitter.send(message);
                // 성공 시 로그 생략 (성능 최적화)
                if (log.isDebugEnabled()) {
                    log.debug("Event sent: " + eventType + ", accountId=" + accountId);
                }
            } catch (Exception sendError) {
                // 전송 실패 시에만 로그
                log.warn("Kafka send failed: " + eventType + ", accountId=" + accountId, sendError);
            }

        } catch (Exception e) {
            log.warn("Failed to serialize event: " + eventType + " for accountId=" + accountId, e);
        }
    }
}
