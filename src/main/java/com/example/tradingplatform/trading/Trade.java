package com.example.tradingplatform.trading;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(
        String id,
        String instrument,
        String buyOrderId,
        String sellOrderId,
        String buyerUserId,
        String sellerUserId,
        BigDecimal price,
        BigDecimal quantity,
        Instant executedAt
) {
}
