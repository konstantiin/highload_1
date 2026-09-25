# Architecture

This document describes the current first-iteration architecture of the trading platform.

The application is intentionally a monolith for now. Future services are represented as Java packages with clear APIs, so later they can be moved behind network boundaries with less reshaping.

## Current Scope

The system provides:

- user registration and plain-text password login checks;
- user roles;
- cash and asset balances;
- limit order placement and cancellation;
- in-memory order matching;
- synthetic market data generation every 10 seconds;
- in-memory audit logging;
- a small Python CLI wrapper over the REST API.

The system intentionally does not currently provide:

- access tokens;
- KYC;
- password hashing;
- real market API integration;
- persistent orders, trades, or audit logs.

Those parts can return later after the core trading behavior is easier to test.

## Deployment View

The application is started by Docker Compose. The Spring Boot application runs in one container and PostgreSQL runs in another.

```text
+-----------------------+              +-----------------------+
| Host machine          |              | Docker network        |
|                       |              |                       |
|  Python CLI           | HTTP :8080   |  trading-platform     |
|  tools/trading_cli.py +------------->+  Spring Boot monolith |
|                       |              |                       |
|  curl / browser       | HTTP :8080   |           | JDBC      |
|                       +------------->+           v           |
|                       |              |  postgres:16-alpine   |
+-----------------------+              +-----------------------+
```

Compose services:

```text
+-------------------+       depends_on healthy       +-------------------+
| trading-platform  |------------------------------->| postgres          |
| port 8080         |                                | port 5432         |
| Java 21 runtime   |                                | database:         |
| Spring Boot       |                                | trading_platform  |
+-------------------+                                +-------------------+
```

## Package View

The code is organized by future service boundary.

```text
com.example.tradingplatform
|
+-- api
|   +-- AuthController
|   +-- TradingController
|   +-- AuditController
|   +-- ApiExceptionHandler
|
+-- auth
|   +-- AuthService
|   +-- User
|   +-- AuthenticatedUser
|   +-- Balance
|   |
|   +-- persistence
|       +-- UserEntity
|       +-- UserRepository
|
+-- trading
|   +-- TradingService
|   +-- Order
|   +-- Trade
|   +-- MarketStats
|   +-- OrderSide
|   +-- OrderStatus
|
+-- market
|   +-- MarketService
|
+-- logging
    +-- AuditLogService
    +-- AuditEvent
```

Dependency direction:

```text
             +----------------+
             |      api       |
             +-------+--------+
                     |
          +----------+-----------+
          |          |           |
          v          v           v
   +------+---+  +---+------+  +-+---------+
   | auth     |  | trading  |  | logging   |
   +------+---+  +---+------+  +-----------+
          ^          |
          |          v
          |     +----+----+
          |     | market  |
          |     +---------+
          |
          v
   +-------------+
   | PostgreSQL  |
   +-------------+
```

Notes:

- `api` is the REST boundary.
- `auth` owns users, roles, passwords, cash, and asset balances.
- `trading` owns orders, trades, matching, and calls `auth` to reserve/release balances.
- `market` is an internal scheduled producer. There is no public market tick API.
- `logging` is an in-memory audit/event sink used by other packages.

## REST API Boundary

The API is deliberately simple. There are no tokens right now. Endpoints that need a user identity receive credentials in headers:

```text
X-Username: alice
X-Password: alice
```

Authentication is a direct plain-text password comparison against the stored user row.

Main endpoints:

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
GET  /api/auth/users
GET  /api/auth/balances/{userId}

POST /api/trading/orders
POST /api/trading/orders/{orderId}/cancel
GET  /api/trading/orders
GET  /api/trading/trades
GET  /api/trading/stats

GET  /api/logs
```

There is intentionally no endpoint like `/api/market/tick`.

## Data Ownership

```text
+----------------------+----------------------+------------------+
| Data                 | Owner package        | Storage          |
+----------------------+----------------------+------------------+
| Users                | auth                 | PostgreSQL       |
| Roles                | auth                 | PostgreSQL       |
| Plain-text passwords | auth                 | PostgreSQL       |
| Cash balances        | auth                 | PostgreSQL       |
| Asset balances       | auth                 | PostgreSQL       |
| Orders               | trading              | In memory        |
| Trades               | trading              | In memory        |
| Audit events         | logging              | In memory        |
| Market generator cfg | market / Spring cfg  | application.yml  |
+----------------------+----------------------+------------------+
```

PostgreSQL schema is currently managed by Hibernate `ddl-auto: update`. A small `schema.sql` removes obsolete first-iteration columns/tables from previous runs:

- old KYC columns;
- old access token table.

## Registration And Login Flow

Registration creates a trader user and initial balances.

```text
Client
  |
  | POST /api/auth/register { username, password }
  v
AuthController
  |
  v
AuthService
  |
  +-- check username uniqueness
  +-- create user with role TRADER
  +-- set initial cash: 100000.00
  +-- set initial STUB asset balance: 100.00
  |
  v
PostgreSQL
```

Login does not create a token. It only verifies credentials and returns the user.

```text
Client
  |
  | POST /api/auth/login { username, password }
  v
AuthController
  |
  v
AuthService
  |
  +-- load user by username
  +-- compare plain-text password
  +-- return user if valid
```

## Order Placement Flow

Buy and sell orders use the same internal path.

```text
Client
  |
  | POST /api/trading/orders
  | X-Username / X-Password
  v
TradingController
  |
  v
TradingService
  |
  +-- AuthService.authenticate(username, password)
  |
  +-- reserve balance
  |     |
  |     +-- BUY: reserve cash = price * quantity
  |     +-- SELL: reserve asset quantity
  |
  +-- create in-memory order
  +-- try matching order book
  +-- write audit event
  |
  v
Response: order state
```

## Matching Flow

The matching engine is currently in-memory and synchronized in `TradingService`.

```text
New order placed
  |
  v
Find best BUY and best SELL for instrument
  |
  v
Are both present and buy.price >= sell.price?
  |
  +-- no --> stop
  |
  +-- yes
       |
       v
  Execute quantity = min(buy.remaining, sell.remaining)
       |
       v
  Execution price = older resting order price
       |
       v
  Update balances through AuthService
       |
       v
  Mark orders FILLED or PARTIALLY_FILLED
       |
       v
  Store Trade in memory and write audit event
       |
       v
  Repeat matching loop
```

Balance behavior:

```text
BUY order:
  reserve max cash at limit price
  if execution price is lower than limit price, refund the difference
  receive asset quantity

SELL order:
  reserve asset quantity
  receive cash after execution
```

## Market Data Producer

The market package simulates a future third-party market feed. Today it is a scheduled Spring service.

```text
Spring scheduler
  |
  | every 10 seconds
  v
MarketService.generateStubMarketData()
  |
  +-- reads TradingService.stats("STUB")
  +-- computes synthetic side, price, quantity
  +-- submits order through TradingService.placeSyntheticOrder(...)
  +-- writes audit event
```

Current configuration:

```text
MARKET_STUB_GENERATOR_DELAY=10000
MARKET_STUB_GENERATOR_INITIAL_DELAY=10000
```

Future direction:

```text
Current:
  scheduler -> stub generator -> TradingService

Future:
  scheduler/subscription -> Binance or other API client -> normalized market event -> TradingService
```

## Audit Logging

Audit logging is in memory for now.

```text
AuthService     ----+
TradingService  ----+----> AuditLogService ----> in-memory event list
MarketService   ----+
```

Events can be queried by:

- time range;
- required tags.

Example tags:

- `auth`;
- `security`;
- `trading`;
- `order`;
- `trade`;
- `balance`;
- `market`.

## CLI Wrapper

The Python CLI is outside Docker and talks to the REST API.

```text
tools/trading_cli.py
  |
  +-- standard library only
  +-- reads TRADING_BASE_URL
  +-- reads TRADING_USERNAME
  +-- reads TRADING_PASSWORD
  |
  v
HTTP REST API on localhost:8080
```

Example:

```bash
python tools/trading_cli.py register alice alice
python tools/trading_cli.py --username alice --password alice order BUY 100.00 1.00
python tools/trading_cli.py --username alice --password alice orders
```

## Evolution Plan

The current package boundaries are meant to map to future services.

```text
Current monolith:

+--------------------------------------------------+
| Spring Boot process                              |
|                                                  |
|  api -> auth -> PostgreSQL                       |
|      -> trading -> auth                          |
|      -> logging                                  |
|      -> market -> trading                        |
+--------------------------------------------------+

Future microservices:

+----------+      +----------+      +-------------+
| API edge |----->| Auth     |----->| Auth DB     |
+----------+      +----------+      +-------------+
     |
     v
+----------+      +----------+
| Trading  |----->| Order DB |
+----------+      +----------+
     ^
     |
+----------+      +-------------------+
| Market   |<-----| Third-party API   |
+----------+      +-------------------+
     |
     v
+----------+
| Logging  |
+----------+
```

Likely next persistence steps:

- persist orders;
- persist trades;
- persist audit logs;
- replace `ddl-auto: update` with explicit migrations;
- add proper password hashing;
- restore real authentication tokens or sessions.
