#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""档位溢价回补。

只做搬运：清单问服务端要（/premium-pool），日 K 让服务端取（/daily-bars），
分组、脏值守卫、加权合成本身一律留在 Java 里。
脚本里不复制一份打分逻辑，是对上一轮回补那次踩坑的直接回应。

    EMOTION_PASSWORD=xxx python3 backfill_premium_tiers.py --start 2026-08-18 --end 2026-09-04

一只票一次请求覆盖整个窗口，所以请求数 = 唯一代码数 + 交易日数，与天数无关。
"""
import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.error
import urllib.request

LEAD_IN_DAYS = 12  # 窗口第一天也要有前收可比，所以往前多要一段日历日


def api(base, token, path, payload=None, retries=1):
    """一次 HTTP 调用。上游偶尔抽风，重试一次；再不行就抛，让调用方决定跳过还是中止。"""
    url = base + path
    for attempt in range(retries + 1):
        try:
            data = None if payload is None else json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(url, data=data)
            req.add_header("Authorization", "Bearer " + token)
            if data is not None:
                req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            if body.get("code") not in (0, 200, None):
                raise RuntimeError("%s -> %s" % (path, body.get("message") or body))
            return body.get("data")
        except (urllib.error.URLError, TimeoutError, RuntimeError) as err:
            if attempt >= retries:
                raise
            sys.stderr.write("  重试 %s: %s\n" % (path, err))
            time.sleep(1.0)


def login(base, user, password):
    req = urllib.request.Request(base + "/api/auth/login",
                                 data=json.dumps({"username": user, "password": password}).encode("utf-8"))
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    data = body.get("data") or {}
    if not data.get("token"):
        raise SystemExit("登录失败：%s" % body)
    return data["token"]


def trading_days(base, token, start, end):
    """交易日序列取自上证指数自己的日 K——系统里没有交易日历表，这是唯一不引新依赖的问法。"""
    bars = api(base, token, "/api/market/daily-bars?symbol=sh000001&start=%s&end=%s" % (start, end))
    return [bar["date"] for bar in bars if bar.get("date")]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--user", default="gaofeng")
    parser.add_argument("--password", default=os.environ.get("EMOTION_PASSWORD"),
                        help="登录口令。不给则读环境变量 EMOTION_PASSWORD")
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--dry-run", action="store_true", help="只取数并打印，不写库")
    args = parser.parse_args()

    # 空口令打到 /api/auth/login 只会回一句没头没脑的"登录失败"，在这里拦掉。
    if not args.password:
        raise SystemExit("口令没给：--password 或环境变量 EMOTION_PASSWORD")

    base, token = args.base, login(args.base, args.user, args.password)
    fetch_start = shift(args.start, -LEAD_IN_DAYS)
    # 往前多要一段：窗口第一天也要有"上一交易日"，否则它没有昨日池可比
    span = trading_days(base, token, fetch_start, args.end)
    days = [day for day in span if day >= args.start]
    if not days:
        raise SystemExit("%s ~ %s 之间没有交易日" % (args.start, args.end))
    print("窗口内交易日 %d 天：%s .. %s" % (len(days), days[0], days[-1]))

    # 1) 逐日清单：哪天要给哪些票拉日 K（首板不在里面，服务端已经筛掉了）
    pools = {}
    for day in days:
        prev = previous(span, day)
        if prev is None:
            print("%s 在前面 %d 天里找不到上一交易日，跳过" % (day, LEAD_IN_DAYS))
            continue
        pool = api(base, token, "/api/market/premium-pool?date=%s" % day)
        if pool.get("prevTradeDate") != prev:
            # 两边对"上一交易日"的判断必须一致，否则拉回来的是另一天的池子
            print("%s 昨日池对不上：服务端给 %s，日 K 序列给 %s，跳过"
                  % (day, pool.get("prevTradeDate"), prev))
            continue
        items = pool.get("items") or []
        if not items:
            print("%s 昨日池(%s)里没有非首板，跳过" % (day, prev))
            continue
        pools[day] = {"prev": prev, "items": items}

    # 2) 一只一次请求：整窗口只拉一遍，逐日的涨跌幅从同一份 bar 序列里取
    symbols = {}
    for day, pool in pools.items():
        for item in pool["items"]:
            symbols[item["symbol"]] = item["code"]
    print("唯一代码 %d 只，逐只一次日 K（%d 线程）" % (len(symbols), args.workers))

    fetched = {}

    def pull(symbol):
        path = "/api/market/daily-bars?symbol=%s&start=%s&end=%s" % (symbol, fetch_start, args.end)
        try:
            return symbol, api(base, token, path, retries=2)
        except Exception as err:  # 一只拉不到只是那几只不计入 matched，不能整批废掉
            sys.stderr.write("  %s 日 K 失败：%s\n" % (symbol, err))
            return symbol, None

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool_threads:
        for symbol, bars in pool_threads.map(pull, sorted(symbols)):
            fetched[symbol] = bars

    # 3) 逐日回 POST。只有"前一根正好是昨日池那天"才算数，跨停牌的涨幅不是当日涨幅
    missing = 0
    for day in sorted(pools):
        pool = pools[day]
        prev = pool["prev"]
        pct = {}
        dropped = []
        for item in pool["items"]:
            bars = fetched.get(item["symbol"])
            code = item["code"]
            hit = index_of(bars, day)
            if hit is None or hit == 0 or bars[hit - 1]["date"] != prev:
                dropped.append(code)
                continue
            value = bars[hit].get("pct")
            if value is None:
                dropped.append(code)
                continue
            pct[code] = value
        missing += len(dropped)
        print("%s 昨日池(%s) 非首板 %d 只，取到涨幅 %d 只%s"
              % (day, prev, len(pool["items"]), len(pct),
                 "" if not dropped else "，缺 %s" % ",".join(dropped)))
        if args.dry_run:
            continue
        try:
            tiers = api(base, token, "/api/market/premium-tiers", {"tradeDate": day, "pct": pct})
        except Exception as err:
            print("  %s 写入失败：%s" % (day, err))
            continue
        line = " / ".join("%s板 %d/%d %s%%" % (row["label"], row["matched"], row["stockCount"], row["avgPct"])
                          for row in tiers["tiers"])
        groups = " ".join("%s=%s" % (row["label"][:2], row["score"]) for row in tiers["groups"])
        print("  档位 %s ｜ %s ｜ 合成 %s%% ｜ %s ｜ 首板 %d 家不计入"
              % (line, groups, tiers["weightedPct"], tiers["structure"], tiers["firstBoard"]))

    print("完成。没取到涨幅的样本共 %d 只次——matched 与 stock_count 不等的那天要单独看一眼" % missing)


def shift(date, days):
    import datetime
    return (datetime.date.fromisoformat(date) + datetime.timedelta(days=days)).isoformat()


def previous(span, date):
    position = span.index(date)
    return span[position - 1] if position > 0 else None


def index_of(bars, date):
    if not bars:
        return None
    for position, bar in enumerate(bars):
        if bar.get("date") == date:
            return position
    return None


if __name__ == "__main__":
    main()
