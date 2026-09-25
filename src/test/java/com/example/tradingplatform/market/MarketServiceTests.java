package com.example.tradingplatform.market;

import com.example.tradingplatform.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "market.stub-generator.initial-delay=600000")
class MarketServiceTests {
    @Autowired
    MarketService marketService;

    @Autowired
    TradingService tradingService;

    @Test
    void backgroundProducerLogicPlacesSyntheticOrderIntoOrderBook() {
        var order = marketService.generateSyntheticOrder("STUB");

        assertThat(order.synthetic()).isTrue();
        assertThat(tradingService.stats("STUB").openOrderCount()).isGreaterThanOrEqualTo(1);
    }
}
