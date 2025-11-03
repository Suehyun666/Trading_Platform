package com.hts.account.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountRecord(
        long accountId,
        String accountNo,
        BigDecimal balance,
        BigDecimal reserved,
        String currency,
        String status,
        LocalDateTime updatedAt
) {}

