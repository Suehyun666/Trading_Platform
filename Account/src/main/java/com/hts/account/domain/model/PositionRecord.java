package com.hts.account.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionRecord(
    Long id,
    Long accountId,
    String symbol,
    BigDecimal quantity,
    BigDecimal avgPrice,
    BigDecimal realizedPnl,
    LocalDateTime updatedAt
) {
}
