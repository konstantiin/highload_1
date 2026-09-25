package com.example.tradingplatform.market;

import com.example.tradingplatform.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "market.stub-generator.initial-delay=600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
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
