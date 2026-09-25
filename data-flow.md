# Data Flow Charts

This document shows the current data flows of the first-iteration trading platform.

Legend:

```text
[External]     external actor or tool
(Component)    Java package / service / controller
{Store}        persisted or in-memory data store
-->            request / call / data movement
<--            response
```

## Level 0 Context Flow

```text
                           +-----------------------------+
                           | Scheduled market generator  |
                           | (market.MarketService)      |
                           +--------------+--------------+
                                          |
                                          | synthetic orders
                                          v
+----------------+       HTTP       +-----+--------------------+       JDBC       +-------------+
| User / CLI     |----------------->| Spring Boot monolith     |---------------->| PostgreSQL  |
| curl / Python  |<-----------------| REST + package services  |<----------------| user data   |
+----------------+       JSON       +-----+--------------------+                 +-------------+
                                          |
                                          | audit events, orders, trades
                                          v
                                  +-------+--------+
                                  | In-memory data |
                                  | orders/logs    |
                                  +----------------+
```

## Registration Flow

```text
[Client]
  |
  | POST /api/auth/register
  | body: username, password
  v
(AuthController)
  |
  | registerTrader(username, password)
  v
(AuthService)
  |
  +--> {PostgreSQL: app_users}
  |      check username uniqueness
  |
  +--> {PostgreSQL: app_users}
  |      insert user:
  |      - role TRADER
  |      - plain-text password
  |      - initial cash 100000.00
  |
  +--> {PostgreSQL: asset_balances}
  |      insert STUB balance 100.00
  |
  +--> (AuditLogService)
         write USER_REGISTERED to in-memory log

[Client] <-- user JSON
```

## Login / Password Check Flow

There are no auth tokens in the current iteration. Login verifies a password and returns the user.

```text
[Client]
  |
  | POST /api/auth/login
  | body: username, password
  v
(AuthController)
  |
  | login(username, password)
  v
(AuthService)
  |
  +--> {PostgreSQL: app_users}
  |      find user by username
  |
  +--> compare request password with stored plain-text password
  |
  +--> (AuditLogService)
         write LOGIN_SUCCEEDED or LOGIN_FAILED

[Client] <-- user JSON or HTTP 400
```

Credential headers for protected endpoints:

```text
X-Username: alice
X-Password: alice
```

## Read Current User Flow

```text
[Client]
  |
  | GET /api/auth/me
  | headers: X-Username, X-Password
  v
(AuthController)
  |
  v
(AuthService.authenticate)
  |
  +--> {PostgreSQL: app_users}
         load user, compare password

[Client] <-- authenticated user JSON
```

## User List Flow

Only users with role `AUDITOR` can list all users.

```text
[Client]
  |
  | GET /api/auth/users
  | headers: X-Username, X-Password
  v
(AuthController)
  |
  v
(AuthService)
  |
  +--> {PostgreSQL: app_users}
  |      authenticate caller
  |
  +--> check role AUDITOR
  |
  +--> {PostgreSQL: app_users, user_roles}
         load all users

[Client] <-- user list JSON
```

## Balance Read Flow

A user can read their own balance. An auditor can read any balance.

```text
[Client]
  |
  | GET /api/auth/balances/{userId}
  | headers: X-Username, X-Password
  v
(AuthController)
  |
  v
(AuthService)
  |
  +--> {PostgreSQL: app_users}
  |      authenticate caller
  |
  +--> permission check:
  |      caller.id == userId OR caller has AUDITOR
  |
  +--> {PostgreSQL: app_users}
  |      read cash
  |
  +--> {PostgreSQL: asset_balances}
         read asset quantities

[Client] <-- balance JSON
```

## Place BUY Order Flow

```text
[Client]
  |
  | POST /api/trading/orders
  | headers: X-Username, X-Password
  | body: side=BUY, price, quantity, instrument
  v
(TradingController)
  |
  v
(TradingService.placeOrder)
  |
  +--> (AuthService.authenticate)
  |      |
  |      +--> {PostgreSQL: app_users}
  |             compare password
  |
  +--> (AuthService.reserveCash)
  |      |
  |      +--> {PostgreSQL: app_users}
  |             cash = cash - price * quantity
  |
  +--> {In-memory orders}
  |      store BUY order
  |
  +--> (TradingService.match)
  |      try to execute against existing SELL orders
  |
  +--> (AuditLogService)
         write ORDER_PLACED / TRADE_EXECUTED / BALANCE_CHANGED

[Client] <-- order JSON
```

## Place SELL Order Flow

```text
[Client]
  |
  | POST /api/trading/orders
  | headers: X-Username, X-Password
  | body: side=SELL, price, quantity, instrument
  v
(TradingController)
  |
  v
(TradingService.placeOrder)
  |
  +--> (AuthService.authenticate)
  |      |
  |      +--> {PostgreSQL: app_users}
  |             compare password
  |
  +--> (AuthService.reserveAsset)
  |      |
  |      +--> {PostgreSQL: asset_balances}
  |             asset = asset - quantity
  |
  +--> {In-memory orders}
  |      store SELL order
  |
  +--> (TradingService.match)
  |      try to execute against existing BUY orders
  |
  +--> (AuditLogService)
         write ORDER_PLACED / TRADE_EXECUTED / BALANCE_CHANGED

[Client] <-- order JSON
```

## Matching And Trade Execution Flow

```text
(TradingService.match)
  |
  v
{In-memory orders}
  |
  +--> find best BUY:
  |      highest price, earliest createdAt
  |
  +--> find best SELL:
  |      lowest price, earliest createdAt
  |
  v
+----------------------------------+
| buy.price >= sell.price ?        |
+----------------------------------+
       | no
       v
     stop

       | yes
       v
quantity = min(buy.remaining, sell.remaining)
price = older resting order price
       |
       v
(AuthService.applyTrade)
       |
       +--> BUY side:
       |      {PostgreSQL: asset_balances}
       |      buyer asset += quantity
       |
       +--> SELL side:
       |      {PostgreSQL: app_users}
       |      seller cash += price * quantity
       |
       +--> BUY refund if limit price > execution price:
              {PostgreSQL: app_users}
              buyer cash += refund
       |
       v
{In-memory orders}
  update remaining quantities and statuses
       |
       v
{In-memory trades}
  append trade
       |
       v
(AuditLogService)
  append trade and balance events
       |
       v
repeat matching loop
```

## Cancel Order Flow

```text
[Client]
  |
  | POST /api/trading/orders/{orderId}/cancel
  | headers: X-Username, X-Password
  v
(TradingController)
  |
  v
(TradingService.cancelOrder)
  |
  +--> (AuthService.authenticate)
  |      |
  |      +--> {PostgreSQL: app_users}
  |
  +--> {In-memory orders}
  |      load order
  |
  +--> permission check:
  |      owner OR AUDITOR
  |
  +--> release reservation:
  |      BUY  -> AuthService.releaseCash
  |      SELL -> AuthService.releaseAsset
  |
  +--> {In-memory orders}
  |      status = CANCELLED
  |
  +--> (AuditLogService)
         write ORDER_CANCELLED

[Client] <-- cancelled order JSON
```

## Market Data Generation Flow

Market generation is internal. Users do not call this flow by REST API.

```text
(Spring scheduler)
  |
  | every 10 seconds
  v
(MarketService.generateStubMarketData)
  |
  +--> (TradingService.stats)
  |      |
  |      +--> {In-memory orders}
  |      |      read real open order volume
  |      |
  |      +--> {In-memory trades}
  |             read average trade price
  |
  +--> compute synthetic order:
  |      - instrument: STUB
  |      - side: alternates from trade count
  |      - price: average price +/- 1%
  |      - quantity: derived from real open volume
  |
  +--> (AuthService.findFirstByRole MARKET)
  |      |
  |      +--> {PostgreSQL: app_users, user_roles}
  |             find market user
  |
  +--> (TradingService.placeSyntheticOrder)
  |      |
  |      +--> reserve market user balance
  |      +--> {In-memory orders}
  |      +--> matching loop
  |
  +--> (AuditLogService)
         write MARKET_DATA_GENERATED
```

## Order And Trade Query Flow

```text
[Client]
  |
  | GET /api/trading/orders or /api/trading/trades
  | headers: X-Username, X-Password
  v
(TradingController)
  |
  v
(TradingService)
  |
  +--> (AuthService.authenticate)
  |      |
  |      +--> {PostgreSQL: app_users}
  |
  +--> role check:
  |      AUDITOR sees all
  |      regular user sees own orders/trades
  |
  +--> {In-memory orders/trades}
         filter and return

[Client] <-- order/trade list JSON
```

## Market Stats Query Flow

Stats are public in the current iteration.

```text
[Client]
  |
  | GET /api/trading/stats?instrument=STUB
  v
(TradingController)
  |
  v
(TradingService.stats)
  |
  +--> {In-memory orders}
  |      count open orders
  |      sum real open order volume
  |
  +--> {In-memory trades}
         calculate average trade price

[Client] <-- market stats JSON
```

## Audit Log Query Flow

```text
[Client]
  |
  | GET /api/logs?from=...&to=...&tags=trading,trade
  | headers: X-Username, X-Password
  v
(AuditController)
  |
  +--> (AuthService.authenticate)
  |      |
  |      +--> {PostgreSQL: app_users}
  |
  +--> check role AUDITOR
  |
  +--> (AuditLogService.query)
         |
         +--> {In-memory audit events}
              filter by time range and required tags

[Client] <-- audit event list JSON
```

## Storage Data Flow Summary

```text
                        +--------------------------+
                        | PostgreSQL               |
                        |                          |
                        | app_users                |
                        | user_roles               |
                        | asset_balances           |
                        +------------+-------------+
                                     ^
                                     |
                                     | JPA repositories
                                     |
+-------------+       calls         +-------------+
| trading     |-------------------->| auth        |
| service     |                     | service     |
+------+------+                     +------+------+
       |                                   |
       | in-memory                         | audit writes
       v                                   v
+------+----------+                 +------+----------+
| orders/trades   |                 | audit events    |
| ConcurrentHash  |                 | CopyOnWriteList |
| + ArrayList     |                 | in memory       |
+-----------------+                 +-----------------+
```

## Failure Flow

Most validation and permission errors use `IllegalArgumentException`, which is converted by `ApiExceptionHandler`.

```text
Controller / Service
  |
  | throws IllegalArgumentException
  v
(ApiExceptionHandler)
  |
  v
HTTP 400 JSON:
  timestamp
  status
  error
  message
```
