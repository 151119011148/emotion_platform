#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析通达信本地配置文件 → 结构化 CSV。

输入（emotion-server/src/main/resources/tongdaxin/）：
  tdxhy.cfg  通达信行业配置（A股全量个股 → 通达信T行业码 + 研究X码），`|` 分隔 6 列
  tdxbk.cfg  通达信概念板块代码→名称清单（类型1概念/2风格/3其他）

输出（同目录 generated/）：
  tdx_concept.csv          概念板块 (code, name, type)
  tdx_stock_industry.csv   个股行业映射 (market, code, market_suffix, t_code, x_code)
  tdx_industry_codes.csv   T码清单+覆盖数（不含中文名，见 README 缺口说明）

本脚本只解析结构，不改动任何业务代码。字段行名/成分股缺口见解析结论。
"""
import collections
import csv
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "emotion-server", "src", "main", "resources", "tongdaxin")
OUT = os.path.join(SRC, "generated")


def parse_hy(rows):
    """tdxhy.cfg → 每只股票一行。列：市场|股票码|T行业码|_|_|X研究码"""
    market = {"0": "SZ", "1": "SH", "2": "BJ"}
    out = []
    for r in rows:
        if len(r) != 6:
            continue
        mcode, code, tcode, _b1, _b2, xcode = [c.strip() for c in r]
        if not code.isdigit():
            continue
        suffix = code[-2:] if len(code) >= 2 else code
        out.append((market.get(mcode, mcode), code, suffix, tcode, xcode))
    return out


def parse_bk(rows):
    """tdxbk.cfg → 概念板块 (type, code, name)"""
    out = []
    for r in rows:
        if len(r) < 3:
            continue
        t, code, name = r[0].strip(), r[1].strip(), r[2].strip()
        if not code:
            continue
        flag = r[3].strip() if len(r) > 3 else ""
        out.append((t, code, name, flag))
    return out


def write(csv_path, header, rows):
    os.makedirs(OUT, exist_ok=True)
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        for r in rows:
            w.writerow(r)
    print("wrote %s (%d rows)" % (os.path.relpath(csv_path), len(rows)))


def main():
    hy = [
        l.strip().split("|")
        for l in open(os.path.join(SRC, "tdxhy.cfg"), encoding="gbk", errors="replace")
        if l.strip()
    ]
    bk = [
        l.strip().split("|")
        for l in open(os.path.join(SRC, "tdxbk.cfg"), encoding="gbk", errors="replace")
        if l.strip()
    ]

    stocks = parse_hy(hy)
    concepts = parse_bk(bk)

    T = collections.Counter(s[3] for s in stocks if s[3])
    bylen = collections.defaultdict(list)
    for t, n in T.items():
        bylen[len([c for c in t if c.isdigit()])].append((t, n))
    print("== 通达信T行业码分级（tdxhy.cfg）==")
    for L in sorted(bylen):
        codes = bylen[L]
        print("   T+%d位: %d个码, 共%d只" % (L, len(codes), sum(n for _, n in codes)))
    print("== 研究X码 ==", len({s[4] for s in stocks if s[4]}), "个")
    print("== 概念板块（tdxbk.cfg）==", dict(collections.Counter(c[0] for c in concepts)))

    write(os.path.join(OUT, "tdx_concept.csv"),
          ["type", "code", "name", "flag"], concepts)
    write(os.path.join(OUT, "tdx_stock_industry.csv"),
          ["market", "code", "market_suffix", "t_code", "x_code"], stocks)
    trows = sorted(((t, n) for t, n in T.items()), key=lambda x: (-x[1], x[0]))
    write(os.path.join(OUT, "tdx_industry_codes.csv"),
          ["t_code", "stock_count"], trows)


if __name__ == "__main__":
    main()