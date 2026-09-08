#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""异动监管事件回补。

和档位溢价那次同一套组织方式：脚本只做搬运和并发，判定全留在 Java 里。
一只票一个请求就能覆盖整个窗口（服务端按 begin/end 要公告），所以请求数 = 唯一代码数，
与回补多少天无关。落库走 (stock_code, art_code) 幂等键，重跑不会产生第二份事件。

    EMOTION_PASSWORD=xxx python3 backfill_surveillance.py --start 2026-08-17 --end 2026-09-04

公告窗口要比行情窗口再往前 45 个日历日：某天在列的票，靠的可能是六周前的那张公告。
"""
import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta


def api(base, token, path, method="GET", retries=1):
    """一次 HTTP 调用。上游偶尔抽风，重试一次；再不行就抛，让调用方决定跳过还是中止。"""
    url = base + path
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, data=b"" if method == "POST" else None, method=method)
            req.add_header("Authorization", "Bearer " + token)
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            if body.get("code") not in (0, 200, None):
                raise RuntimeError("%s -> %s" % (path, body.get("message") or body))
            return body.get("data")
        except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError) as err:
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--user", default="gaofeng")
    parser.add_argument("--password", default=os.environ.get("EMOTION_PASSWORD"),
                        help="登录口令。不给则读环境变量 EMOTION_PASSWORD")
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--ann-lead", type=int, default=45,
                        help="公告窗口比行情窗口再往前多少个日历日（默认 45，够盖 10 个交易日 + 长假）")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--dry-run", action="store_true", help="只列跟踪集合，不打上游")
    args = parser.parse_args()

    # 空口令打到 /api/auth/login 只会回一句没头没脑的"登录失败"，在这里拦掉。
    if not args.password:
        raise SystemExit("口令没给：--password 或环境变量 EMOTION_PASSWORD")

    base = args.base.rstrip("/")
    token = login(base, args.user, args.password)

    ann_start = (date.fromisoformat(args.start) - timedelta(days=args.ann_lead)).isoformat()

    codes = api(base, token, "/api/market/surveillance/tracked?start=%s&end=%s" % (args.start, args.end))
    print("跟踪集合 %d 只（窗口内非首板涨停池 ∪ 表里已有事件的代码），公告窗口 %s~%s"
          % (len(codes), ann_start, args.end))
    if args.dry_run:
        print("、".join(codes))
        return

    def pull(code):
        path = "/api/market/surveillance/refresh?start=%s&end=%s&codes=%s" % (
            urllib.parse.quote(ann_start), urllib.parse.quote(args.end), urllib.parse.quote(code))
        try:
            return code, api(base, token, path, method="POST", retries=2)[0]
        except Exception as err:  # 一只拉不到不该让整批废掉：少的那几只会在下面的逐日名单里露出来
            sys.stderr.write("  %s 公告失败：%s\n" % (code, err))
            return code, None

    outcomes = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as threads:
        for code, outcome in threads.map(pull, codes):
            outcomes[code] = outcome

    failed = [code for code, outcome in outcomes.items() if outcome is None or not outcome.get("ok")]
    events = sum((outcome or {}).get("written", 0) for outcome in outcomes.values())
    signals = sum((outcome or {}).get("unmatchedSignals", 0) for outcome in outcomes.values())
    print("刷新 %d 只：事件 %d 条入库，失败 %d 只，标题像信号却没命中类目码 %d 条"
          % (len(codes), events, len(failed), signals))
    if failed:
        print("失败代码：" + "、".join(sorted(failed)) + "（重跑本脚本即可，落库是幂等的）")
    if signals:
        hits = [code for code, o in outcomes.items() if o and o.get("unmatchedSignals")]
        print("注意：有标题含异常波动/监管却没有类目码的行，上游可能改过结构——判据只认类目码")
        print("     发生在：%s，去公告页对着标题看那一行" % "、".join(sorted(hits)))

    days = api(base, token, "/api/market/daily-bars?symbol=sh000001&start=%s&end=%s" % (args.start, args.end))
    print("\n逐日在列名单（第 9 维的人群）")
    for bar in days:
        day = bar.get("date")
        if not day or day < args.start or day > args.end:
            continue
        view = api(base, token, "/api/market/surveillance?date=%s" % urllib.parse.quote(day))
        items = view.get("items") or []
        if not items:
            print("  %s  无在列（第 9 维不计入分母）" % day)
            continue
        detail = " / ".join("%s %s %s" % (item["name"], item["describe"],
                                          "进分 %+0.2f%%" % item["pct"] if item["pct"] is not None else "缺价")
                            for item in items)
        print("  %s  在列 %d 家 · 均值 %s%% · %s" % (day, view["count"], view["avgPct"], detail))


if __name__ == "__main__":
    main()
