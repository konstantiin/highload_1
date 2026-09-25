package com.example.tradingplatform.api;

import com.example.tradingplatform.market.MarketService;
import com.example.tradingplatform.market.MarketTickResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @PostMapping("/tick")
    public MarketTickResult tick(@RequestParam(defaultValue = "STUB") String instrument) {
        return marketService.tick(instrument);
    }
}
