# Trading Platform Monolith

First iteration of the trading system from `spec.txt`. The application is a Spring Boot monolith, but its code is split into package-level service boundaries that can later become separate microservices.

## Packages

- `auth`: registration, login, opaque access tokens, users, KYC decisions, cash and asset balances.
- `trading`: limit order placement/cancellation, order book matching, trades, balance updates.
- `market`: internal market data producer that wakes every 10 seconds and places synthetic orders into the real order book. Later this package can replace the stub generator with a third-party API subscription/client.
- `logging`: in-memory audit log with time and tag filtering.
- `api`: REST controllers over the package APIs.

## Run

```bash
docker compose up --build
```

The API listens on `http://localhost:8080`.

## Seed Accounts

The in-memory store starts with these accounts:

- `admin` / `admin`: KYC reviewer and auditor.
- `regulator` / `regulator`: auditor.
- `market` / `market`: internal market-maker user.

Registered traders start with:

- cash: `100000.00`
- asset holdings: `100.00 STUB`
- KYC status: `PENDING`

KYC must be `APPROVED` before a trader can place orders. This is the conservative behavior for the first iteration.

## Example Flow

Register a trader:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice","kycText":"passport data"}'
```

Login:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice"}'
```

Approve KYC with the admin token:

```bash
curl -X POST http://localhost:8080/api/auth/kyc/{userId}/decision \
  -H "Authorization: Bearer {adminToken}" \
  -H "Content-Type: application/json" \
  -d '{"status":"APPROVED"}'
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
