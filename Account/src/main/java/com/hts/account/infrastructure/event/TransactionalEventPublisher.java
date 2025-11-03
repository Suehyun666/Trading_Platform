package com.hts.account.infrastructure.event;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * 트랜잭션 커밋 후 이벤트 발행
 *
 * 문제:
 * - Kafka emitter를 @Transactional 내부에서 호출하면
 * - 비동기 스레드가 같은 JTA 트랜잭션 컨텍스트에 접근
 * - "multiple threads active" 에러 발생
 *
 * 해결:
 * - TransactionSynchronizationRegistry 사용
 * - afterCompletion 훅에서 Kafka 발행
 * - 트랜잭션 커밋 후에만 실행 (STATUS_COMMITTED)
 */
@ApplicationScoped
public class TransactionalEventPublisher {

    private static final Logger log = Logger.getLogger(TransactionalEventPublisher.class);

    @Inject
    TransactionSynchronizationRegistry syncRegistry;

    @Inject
    EventPublisher eventPublisher;

    /**
     * 트랜잭션 커밋 후 이벤트 발행
     */
    public void publishAfterCommit(String eventType, long accountId, BigDecimal amount) {
        try {
            syncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // 커밋 전에는 아무것도 안 함
                }

                @Override
                public void afterCompletion(int status) {
                    // 커밋 성공한 경우에만 Kafka 발행
                    if (status == Status.STATUS_COMMITTED) {
                        try {
                            eventPublisher.publish(eventType, accountId, amount);
                        } catch (Exception e) {
                            // 이벤트 발행 실패해도 트랜잭션은 이미 커밋됨
                            log.warn("Event publish failed after commit: " + eventType +
                                    ", accountId=" + accountId, e);
                        }
                    } else {
                        // 롤백된 경우 이벤트 발행 안 함
                        if (log.isDebugEnabled()) {
                            log.debug("Transaction rolled back, event not published: " +
                                     eventType + ", accountId=" + accountId);
                        }
                    }
                }
            });
        } catch (Exception e) {
            // Synchronization 등록 실패 (non-critical)
            log.warn("Failed to register afterCommit hook for event: " + eventType, e);
        }
    }
}
