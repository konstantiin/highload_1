#!/usr/bin/env python3
import argparse
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "http://localhost:8080"


def request(method, base_url, path, username=None, password=None, body=None, query=None):
    url = base_url.rstrip("/") + path
    if query:
        cleaned = {key: value for key, value in query.items() if value is not None}
        if cleaned:
            url += "?" + urlencode(cleaned)

    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if username:
        headers["X-Username"] = username
    if password:
        headers["X-Password"] = password

    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=20) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            payload = json.loads(raw)
            message = payload.get("message", raw)
        except json.JSONDecodeError:
            message = raw
        raise SystemExit(f"HTTP {error.code}: {message}") from error
    except URLError as error:
        raise SystemExit(f"Connection error: {error.reason}") from error


def print_json(value):
    print(json.dumps(value, indent=2, sort_keys=True))


def credentials(args):
    username = args.username or os.getenv("TRADING_USERNAME")
    password = args.password or os.getenv("TRADING_PASSWORD")
    if not username or not password:
        raise SystemExit("Credentials required. Pass --username/--password or set TRADING_USERNAME/TRADING_PASSWORD.")
    return username, password


def register(args):
    return request("POST", args.base_url, "/api/auth/register", body={
        "username": args.username,
        "password": args.password,
    })


def login(args):
    return request("POST", args.base_url, "/api/auth/login", body={
        "username": args.username,
        "password": args.password,
    })


def me(args):
    username, password = credentials(args)
    return request("GET", args.base_url, "/api/auth/me", username=username, password=password)


def users(args):
    username, password = credentials(args)
    return request("GET", args.base_url, "/api/auth/users", username=username, password=password)


def balance(args):
    username, password = credentials(args)
    return request("GET", args.base_url, f"/api/auth/balances/{args.user_id}", username=username, password=password)


def place_order(args):
    username, password = credentials(args)
    return request("POST", args.base_url, "/api/trading/orders", username=username, password=password, body={
        "instrument": args.instrument,
        "side": args.side,
        "price": args.price,
        "quantity": args.quantity,
    })


def cancel_order(args):
    username, password = credentials(args)
    return request("POST", args.base_url, f"/api/trading/orders/{args.order_id}/cancel", username=username, password=password)


def orders(args):
    username, password = credentials(args)
    return request("GET", args.base_url, "/api/trading/orders", username=username, password=password)


def trades(args):
    username, password = credentials(args)
    return request("GET", args.base_url, "/api/trading/trades", username=username, password=password)


def stats(args):
    return request("GET", args.base_url, "/api/trading/stats", query={"instrument": args.instrument})


def logs(args):
    username, password = credentials(args)
    return request("GET", args.base_url, "/api/logs", username=username, password=password, query={
        "from": args.from_time,
        "to": args.to_time,
        "tags": args.tags,
    })


def build_parser():
    parser = argparse.ArgumentParser(description="Small CLI wrapper for the trading platform REST API.")
    parser.add_argument("--base-url", default=os.getenv("TRADING_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--username", help="Username. Can also be set as TRADING_USERNAME.")
    parser.add_argument("--password", help="Password. Can also be set as TRADING_PASSWORD.")

    subparsers = parser.add_subparsers(dest="command", required=True)

    register_parser = subparsers.add_parser("register")
    register_parser.add_argument("username")
    register_parser.add_argument("password")
    register_parser.set_defaults(func=register)

    login_parser = subparsers.add_parser("login")
    login_parser.add_argument("username")
    login_parser.add_argument("password")
    login_parser.set_defaults(func=login)

    me_parser = subparsers.add_parser("me")
    me_parser.set_defaults(func=me)

    users_parser = subparsers.add_parser("users")
    users_parser.set_defaults(func=users)

    balance_parser = subparsers.add_parser("balance")
    balance_parser.add_argument("user_id")
    balance_parser.set_defaults(func=balance)

    order_parser = subparsers.add_parser("order")
    order_parser.add_argument("side", choices=["BUY", "SELL"])
    order_parser.add_argument("price")
    order_parser.add_argument("quantity")
    order_parser.add_argument("--instrument", default="STUB")
    order_parser.set_defaults(func=place_order)

    cancel_parser = subparsers.add_parser("cancel")
    cancel_parser.add_argument("order_id")
    cancel_parser.set_defaults(func=cancel_order)

    orders_parser = subparsers.add_parser("orders")
    orders_parser.set_defaults(func=orders)

    trades_parser = subparsers.add_parser("trades")
    trades_parser.set_defaults(func=trades)

    stats_parser = subparsers.add_parser("stats")
    stats_parser.add_argument("--instrument", default="STUB")
    stats_parser.set_defaults(func=stats)

    logs_parser = subparsers.add_parser("logs")
    logs_parser.add_argument("--from", dest="from_time")
    logs_parser.add_argument("--to", dest="to_time")
    logs_parser.add_argument("--tags", help="Comma-separated tags, for example trading,trade")
    logs_parser.set_defaults(func=logs)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    print_json(args.func(args))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
