package com.example.tradingplatform.auth;

import java.math.BigDecimal;
import java.util.Map;

public record Balance(BigDecimal cash, Map<String, BigDecimal> assets) {
}
