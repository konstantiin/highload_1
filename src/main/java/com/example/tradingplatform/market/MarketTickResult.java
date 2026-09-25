package com.example.tradingplatform.market;

import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.MarketStats;

public record MarketTickResult(MarketStats statsBeforeOrder, Order syntheticOrder) {
}
