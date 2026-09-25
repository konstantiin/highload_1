package com.example.tradingplatform.trading;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
        String id,
        String userId,
        String instrument,
        OrderSide side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal remainingQuantity,
        OrderStatus status,
        boolean synthetic,
        Instant createdAt
) {
    public Order withRemainingQuantity(BigDecimal newRemainingQuantity) {
        OrderStatus newStatus = newRemainingQuantity.signum() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return new Order(id, userId, instrument, side, price, quantity, newRemainingQuantity, newStatus, synthetic, createdAt);
    }

    public Order cancelled() {
        return new Order(id, userId, instrument, side, price, quantity, remainingQuantity, OrderStatus.CANCELLED, synthetic, createdAt);
    }
}
