package com.example.tradingplatform.market;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.User;
import com.example.tradingplatform.logging.AuditLogService;
import com.example.tradingplatform.trading.MarketStats;
import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.OrderSide;
import com.example.tradingplatform.trading.TradingService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

@Service
public class MarketService {
    private final AuthService authService;
    private final TradingService tradingService;
    private final AuditLogService auditLogService;

    public MarketService(AuthService authService, TradingService tradingService, AuditLogService auditLogService) {
        this.authService = authService;
        this.tradingService = tradingService;
        this.auditLogService = auditLogService;
    }

    public MarketTickResult tick(String instrument) {
        MarketStats stats = tradingService.stats(instrument);
        User marketUser = authService.findFirstByRole(AuthService.ROLE_MARKET)
                .orElseThrow();
        BigDecimal basePrice = stats.averageTradePrice();
        BigDecimal volumeFactor = stats.realOpenOrderVolume().max(BigDecimal.ONE);
        BigDecimal quantity = volumeFactor.divide(new BigDecimal("10"), 2, RoundingMode.HALF_UP).max(new BigDecimal("1.00"));
        OrderSide side = stats.tradeCount() % 2 == 0 ? OrderSide.SELL : OrderSide.BUY;
        BigDecimal priceShift = side == OrderSide.SELL ? new BigDecimal("1.01") : new BigDecimal("0.99");
        BigDecimal price = basePrice.multiply(priceShift).setScale(2, RoundingMode.HALF_UP);
        Order order = tradingService.placeSyntheticOrder(marketUser.id(), stats.instrument(), side, price, quantity);
        auditLogService.write("MARKET_TICK", Set.of("market", "trading"), marketUser.id(), "Market maker placed " + side + " order " + order.id());
        return new MarketTickResult(stats, order);
    }
}
