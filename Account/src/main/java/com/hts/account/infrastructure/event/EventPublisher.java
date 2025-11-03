package com.hts.account.infrastructure.event;

import java.math.BigDecimal;

public interface EventPublisher {
    void publish(String eventType, long accountId, BigDecimal amount);
}
