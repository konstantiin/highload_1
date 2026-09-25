package com.example.tradingplatform.api;

import com.example.tradingplatform.trading.MarketStats;
import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.OrderSide;
import com.example.tradingplatform.trading.Trade;
import com.example.tradingplatform.trading.TradingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/trading")
public class TradingController {
    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/orders")
    public Order placeOrder(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password, @Valid @RequestBody PlaceOrderRequest request) {
        return tradingService.placeOrder(username, password, request.instrument(), request.side(), request.price(), request.quantity());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Order cancelOrder(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password, @PathVariable String orderId) {
        return tradingService.cancelOrder(username, password, orderId);
    }

    @GetMapping("/orders")
    public List<Order> orders(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password) {
        return tradingService.listOrders(username, password);
    }

    @GetMapping("/trades")
    public List<Trade> trades(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password) {
        return tradingService.listTrades(username, password);
    }

    @GetMapping("/stats")
    public MarketStats stats(@RequestParam(defaultValue = "STUB") String instrument) {
        return tradingService.stats(instrument);
    }

    public record PlaceOrderRequest(
            String instrument,
            @NotNull OrderSide side,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity
    ) {
    }
}
