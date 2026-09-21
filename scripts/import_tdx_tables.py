#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「海王星板块数据_待解析包」解析为通达信行业/概念关联表并写入 emotion_dashboard。

建表（幂等 CREATE TABLE IF NOT EXISTS）：
  t_industry        通达信行业分类字典（一级/二级/三级全量，来源 incon.dat #TDXNHY）
  t_industry_stock  个股 → 二级行业 映射（来源 tdxhy.cfg，行业码截 T+4）
  t_concept_board   通达信概念板块字典（来源 infoharbor_block.dat GN_ + tdxbk.cfg 全称/类别）
重新填充：
  t_stock_concept   个股 → 概念（覆盖原东财数据），来源 infoharbor GN_ 成分，过滤伪概念

约定：全部「先删后插」幂等；通过 mysql 命令行写库（本机 root/123456/emotion_dashboard）。
伪概念过滤与后端 ConceptIndexService.PSEUDO_CONCEPTS / isPseudo 保持同构，避免题材表被污染。
"""
import collections
import os
import subprocess

R = "emotion-server/src/main/resources/海王星板块数据_待解析包"
DB = ["--protocol=tcp", "--host=127.0.0.1", "-u", "root", "-p123456",
      "--default-character-set=utf8mb4", "-D", "emotion_dashboard"]

# 与 ConceptIndexService 同构的伪概念过滤（仅概念部分适用）
PSEUDO_CONCEPTS = [
    "融资融券", "沪股通", "深股通", "港股通", "转融券",
    "东方财富热股", "热股", "人气榜", "热门",
    "MSCI", "标准普尔", "富时罗素", "罗素", "深证成指", "深证100", "深证300",
    "沪深300", "中证500", "中证1000", "上证180", "上证380", "上证50", "科创50", "创业板指",
    "机构重仓", "基金重仓", "QFII重仓", "社保重仓", "保险重仓", "券商重仓", "信托重仓",
    "证金持股", "汇金持股", "国家队", "养老金持股", "北向资金",
    "昨日涨停", "昨日跌停", "昨日触板", "昨日连板", "昨日炸板",
    "前一日涨停", "连续涨停", "昨日非涨停", "涨停股池", "昨涨停",
    "低价股", "中价股", "高价股", "微盘股", "小盘股", "中盘股", "大盘股", "超大盘",
    "趋势股", "破发股", "破净股",
    "预盈预增", "预增", "扭亏", "业绩预增", "高质押", "股权激励", "ST",
    "次新股", "送转",
    "题材股", "中报首亏", "季报首亏", "年报首亏", "业绩预降", "业绩预减",
]


def run(sql, fetch=False):
    args = ["mysql"] + DB
    if fetch:
        args += ["-N", "-e", sql]
    else:
        args += ["-e", sql]
    out = subprocess.run(args, capture_output=True, text=True).stdout
    return out


def load_names():
    out = run("SELECT DISTINCT code,name FROM t_market_stock", fetch=True)
    return {ln.split("\t")[0]: ln.split("\t")[1].strip()
            for ln in out.splitlines() if "\t" in ln}


def read_incon():
    sec = None; m = {}
    for l in open(os.path.join(R, "incon.dat"), encoding="gbk", errors="replace"):
        s = l.strip()
        if s.startswith("#"):
            sec = s.strip("#").strip(); continue
        if sec == "TDXNHY" and "|" in s:
            c, n = s.split("|", 1); m[c.strip()] = n.strip()
    return m


def read_tdxhy():
    return [l.rstrip().split("|") for l in open(os.path.join(R, "tdxhy.cfg"),
            encoding="gbk", errors="replace") if "|" in l]


def read_infoharbor():
    blocks, cur = [], None
    for l in open(os.path.join(R, "infoharbor_block.dat"), encoding="gbk",
                  errors="replace"):
        s = l.strip()
        if not s:
            continue
        if s.startswith("#"):
            head = [x.strip() for x in s[1:].split(",")]
            if head[0].startswith("GN_"):
                cur = [head[0], head[2] if len(head) > 2 else "", []]
                blocks.append(cur)
            else:
                cur = None
        elif cur is not None:
            cur[2] += [x for x in s.split(",") if x]
    return blocks


def read_tdxbk():
    m = {}
    for l in open(os.path.join(R, "tdxbk.cfg"), encoding="gbk", errors="replace"):
        f = l.rstrip().split("|")
        if len(f) >= 3:
            m[f[1]] = (f[0], f[2])
    return m


def is_pseudo(name):
    if not name:
        return True
    if name.startswith(("昨日", "前日", "最近", "连续", "上周", "本周")):
        return True
    return any(p in name for p in PSEUDO_CONCEPTS)


def lv(code, digits):
    if not code.startswith("T"):
        return ""
    d = "".join(ch for ch in code if ch.isdigit())
    return ("T" + d[:digits]) if len(d) >= digits else ""


DDL = """
CREATE TABLE IF NOT EXISTS t_industry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  industry_code VARCHAR(10) NOT NULL COMMENT '通达信行业码(T0101)',
  industry_name VARCHAR(50) NOT NULL,
  parent_code VARCHAR(10),
  level TINYINT COMMENT '1一级/2二级/3三级',
  UNIQUE KEY uk_industry_code (industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信行业分类字典(海王星/TDX)';

CREATE TABLE IF NOT EXISTS t_industry_stock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(6) NOT NULL,
  name VARCHAR(20),
  market VARCHAR(4) COMMENT '深/沪/北',
  industry_code VARCHAR(10) NOT NULL COMMENT '二级行业码',
  industry_name VARCHAR(50) NOT NULL,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股→通达信二级行业 映射';

CREATE TABLE IF NOT EXISTS t_concept_board (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  board_code VARCHAR(30) NOT NULL COMMENT '板块简称(锂电池/通达信88)',
  board_name VARCHAR(60) NOT NULL COMMENT '板块全称',
  index_code VARCHAR(10),
  category VARCHAR(4) DEFAULT '1' COMMENT '1概念/2风格/3指数',
  UNIQUE KEY uk_board_code (board_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信概念板块字典(海王星/TDX)';
"""


def main():
    names = load_names()
    incon = read_incon()
    tdxhy = read_tdxhy()
    infoharbor = read_infoharbor()
    tdxbk = read_tdxbk()
    print("names %d, incon %d, tdxhy %d, GN_blocks %d, tdxbk %d"
          % (len(names), len(incon), len(tdxhy), len(infoharbor), len(tdxbk)))

    run(DDL)

    # ---- t_industry 字典（全量，含三级）----
    market = {"0": "深", "1": "沪", "2": "北"}
    ind_rows = [(c, n, lv(c, 2) or None,
                 1 if len([x for x in c if x.isdigit()]) == 2 else
                 2 if len([x for x in c if x.isdigit()]) == 4 else 3)
                for c, n in incon.items() if c.startswith("T")]
    sql = ("TRUNCATE t_industry; INSERT INTO t_industry"
           "(industry_code,industry_name,parent_code,level) VALUES "
           + ",".join("('%s','%s',%s,%d)" % (c, n.replace("'", "''"),
                      "'%s'" % p if p else "NULL", lv_)
                      for c, n, p, lv_ in ind_rows))
    run(sql); print("t_industry 写入 %d 行" % len(ind_rows))

    # ---- t_industry_stock 个股→二级行业（截 T+4，去未分类）----
    is_rows = []
    for f in tdxhy:
        if len(f) < 3:
            continue
        mkt, code, tcode = f[0], f[1], f[2]
        sec = lv(tcode, 4)
        if not sec or not code.isdigit():
            continue
        is_rows.append((code, names.get(code, ""), market.get(mkt, mkt),
                        sec, incon.get(sec, "")))
    run("TRUNCATE t_industry_stock")
    for i in range(0, len(is_rows), 500):
        chunk = is_rows[i:i + 500]
        sql = ("INSERT INTO t_industry_stock"
               "(code,name,market,industry_code,industry_name) VALUES "
               + ",".join("('%s','%s','%s','%s','%s')"
                          % (c, n.replace("'", "''"), mk, sc, sn.replace("'", "''"))
                          for c, n, mk, sc, sn in chunk))
        run(sql)
    print("t_industry_stock 写入 %d 行(%d 个二级行业)" % (len(is_rows), 0))

    # ---- t_concept_board 概念板块字典 ----
    cb = []
    for name, idx, _ in infoharbor:
        cn = name[3:] if name.startswith("GN_") else name
        cat, full = tdxbk.get(cn, ("1", cn))
        cb.append((cn, full if full else cn, idx, cat if cat else "1"))
    run("TRUNCATE t_concept_board")
    for i in range(0, len(cb), 500):
        chunk = cb[i:i + 500]
        sql = ("INSERT INTO t_concept_board"
               "(board_code,board_name,index_code,category) VALUES "
               + ",".join("('%s','%s','%s','%s')"
                          % (c.replace("'", "''"), n.replace("'", "''"),
                             ix.replace("'", "''"), cat)
                          for c, n, ix, cat in chunk))
        run(sql)
    print("t_concept_board 写入 %d 行" % len(cb))

    # ---- t_stock_concept 个股→概念（过滤伪概念后覆盖旧东财数据）----
    conc = []
    for name, _, members in infoharbor:
        cn = name[3:] if name.startswith("GN_") else name
        if is_pseudo(cn):
            continue
        cat, full = tdxbk.get(cn, (None, cn))
        cname = full if full else cn
        for x in members:
            if "#" not in x:
                continue
            m, code = x.split("#", 1)
            code = code.strip()
            if not (code.isdigit() and len(code) == 6):
                continue
            conc.append((code, cn, cname, names.get(code, "")))
    # 去重（同 code 同概念只留一条）
    seen = set(); uni = []
    for r in conc:
        k = (r[0], r[1])
        if k in seen:
            continue
        seen.add(k); uni.append(r)
    run("DELETE FROM t_stock_concept")
    for i in range(0, len(uni), 1000):
        chunk = uni[i:i + 1000]
        sql = ("INSERT INTO t_stock_concept(code,concept_code,concept,name) VALUES "
               + ",".join("('%s','%s','%s','%s')"
                          % (c, cc.replace("'", "''"),
                             cn.replace("'", "''"), n.replace("'", "''"))
                          for c, cc, cn, n in chunk)
               + " ON DUPLICATE KEY UPDATE concept=VALUES(concept),name=VALUES(name)")
        run(sql)
    print("t_stock_concept 写入 %d 行(过滤伪概念后, %d 个概念)" % (len(uni),
          len({r[1] for r in uni})))


if __name__ == "__main__":
    main()