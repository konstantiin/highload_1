# Trading Platform Monolith

First iteration of the trading system from `spec.txt`. The application is a Spring Boot monolith, but its code is split into package-level service boundaries that can later become separate microservices.

## Packages

- `auth`: registration, login, opaque access tokens, users, cash and asset balances. User data is stored in PostgreSQL.
- `trading`: limit order placement/cancellation, order book matching, trades, balance updates.
- `market`: internal market data producer that wakes every 10 seconds and places synthetic orders into the real order book. Later this package can replace the stub generator with a third-party API subscription/client.
- `logging`: in-memory audit log with time and tag filtering.
- `api`: REST controllers over the package APIs.

## Run

```bash
docker compose up --build
```

The API listens on `http://localhost:8080`.

Docker Compose starts:

- `trading-platform`: Spring Boot application.
- `postgres`: PostgreSQL database for users, roles, tokens, cash, and asset balances.

The application creates/updates its schema automatically with Hibernate for this first iteration.

## Seed Accounts

The in-memory store starts with these accounts:

- `admin` / `admin`: auditor.
- `regulator` / `regulator`: auditor.
- `market` / `market`: internal market-maker user.

Registered traders start with:

- cash: `100000.00`
- asset holdings: `100.00 STUB`
## Example Flow

You can use the tiny local CLI wrapper instead of raw `curl`:

```bash
python tools/trading_cli.py login admin admin
python tools/trading_cli.py --token {adminToken} users
python tools/trading_cli.py register alice alice
python tools/trading_cli.py --token {traderToken} order BUY 100.00 1.00
```

The CLI reads `TRADING_BASE_URL` and `TRADING_TOKEN` if you do not pass `--base-url` or `--token`.

Register a trader:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice"}'
```

Login:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice"}'
```

Place a limit order:

```bash
curl -X POST http://localhost:8080/api/trading/orders \
  -H "Authorization: Bearer {traderToken}" \
  -H "Content-Type: application/json" \
  -d '{"instrument":"STUB","side":"BUY","price":100.00,"quantity":1.00}'
```

Query audit logs:

```bash
curl "http://localhost:8080/api/logs?tags=trading,trade" \
  -H "Authorization: Bearer {regulatorToken}"
```
