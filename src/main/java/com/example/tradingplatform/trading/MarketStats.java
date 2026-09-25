package com.example.tradingplatform.trading;

import java.math.BigDecimal;

public record MarketStats(
        String instrument,
        BigDecimal realOpenOrderVolume,
        BigDecimal averageTradePrice,
        int openOrderCount,
        int tradeCount
) {
}
