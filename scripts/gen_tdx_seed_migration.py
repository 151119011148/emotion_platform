#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把已入库的通达信 行业/题材↔个股 关联数据导出为初始化 migration SQL。

四张表：t_industry(行业字典) / t_concept_board(题材字典) /
        t_industry_stock(个股→二级行业) / t_stock_concept(个股→概念)。
输出自包含文件：CREATE TABLE IF NOT EXISTS + DELETE + 分批 INSERT，可独立在干净库重放。

用法：python3 scripts/gen_tdx_seed_migration.py
"""
import os
import subprocess

DB = ["--protocol=tcp", "--host=127.0.0.1", "-u", "root", "-p123456",
      "--default-character-set=utf8mb4", "-D", "emotion_dashboard"]
OUT = os.path.join(os.path.dirname(__file__), "..",
                   "emotion-server/src/main/resources/migration",
                   "V33__init_tdx_industry_concept_data.sql")

# (表名, INSERT 列, 注释)
TABLES = [
    ("t_industry",
     ["industry_code", "industry_name", "parent_code", "level"],
     "通达信行业分类字典(海王星 incon.dat #TDXNHY)"),
    ("t_industry_stock",
     ["code", "name", "market", "industry_code", "industry_name"],
     "个股→通达信二级行业 映射(tdxhy.cfg 截 T+4)"),
    ("t_concept_board",
     ["board_code", "board_name", "index_code", "category"],
     "通达信概念板块字典(infoharbor_block GN_ + tdxbk.cfg)"),
    ("t_stock_concept",
     ["code", "concept_code", "concept", "name"],
     "个股→概念 映射(D2 题材表地基, 过滤伪概念)"),
]

DDL_TEMPLATE = {
    "t_industry": """CREATE TABLE IF NOT EXISTS t_industry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  industry_code VARCHAR(10) NOT NULL COMMENT '通达信行业码(T0101)',
  industry_name VARCHAR(50) NOT NULL,
  parent_code VARCHAR(10),
  level TINYINT COMMENT '1一级/2二级/3三级',
  UNIQUE KEY uk_industry_code (industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信行业分类字典(海王星/TDX)';""",
    "t_industry_stock": """CREATE TABLE IF NOT EXISTS t_industry_stock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(6) NOT NULL,
  name VARCHAR(20),
  market VARCHAR(4) COMMENT '深/沪/北',
  industry_code VARCHAR(10) NOT NULL COMMENT '二级行业码',
  industry_name VARCHAR(50) NOT NULL,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股→通达信二级行业 映射';""",
    "t_concept_board": """CREATE TABLE IF NOT EXISTS t_concept_board (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  board_code VARCHAR(30) NOT NULL COMMENT '板块简称(锂电池/通达信88)',
  board_name VARCHAR(60) NOT NULL COMMENT '板块全称',
  index_code VARCHAR(10),
  category VARCHAR(4) DEFAULT '1' COMMENT '1概念/2风格/3指数',
  UNIQUE KEY uk_board_code (board_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信概念板块字典(海王星/TDX)';""",
    "t_stock_concept": """CREATE TABLE IF NOT EXISTS t_stock_concept (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(6) NOT NULL,
  concept_code VARCHAR(12) NOT NULL DEFAULT '' COMMENT '概念码(东财 BKxxxx 或 GN_ 简称)',
  concept VARCHAR(60) NOT NULL DEFAULT '' COMMENT '概念名',
  name VARCHAR(20) DEFAULT '' COMMENT '个股名',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code_concept (code, concept_code),
  KEY idx_code (code),
  KEY idx_concept (concept)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股-概念映射(题材表地基)';""",
}


def fetch(sql):
    out = subprocess.run(["mysql"] + DB + ["-N", "-e", sql],
                         capture_output=True, text=True).stdout
    return [ln.split("\t") for ln in out.splitlines() if ln]


def val(x):
    # mysql -N 把 SQL NULL 输出为字面量 "NULL"
    if x == "NULL":
        return "NULL"
    return "'%s'" % x.replace("\\", "\\\\").replace("'", "''")


def main():
    chunks = []
    header = (
        "-- ====================================================================\n"
        "-- 通达信 行业/题材 ↔ 个股 关联数据 初始化迁移\n"
        "-- 来源：海王星板块数据_待解析包（incon.dat/tdxhy.cfg/infoharbor_block.dat/tdxbk.cfg）\n"
        "-- 生成：scripts/gen_tdx_seed_migration.py + 已入库数据\n"
        "-- 幂等：CREATE TABLE IF NOT EXISTS + DELETE 后重建，可反复重放；需已建库 emotion_dashboard\n"
        "-- 依赖：四张表 DDL 与 schema.sql 保持一致；数据被 import 脚本/handbook 覆盖过也无需重改。\n"
        "-- ====================================================================\n"
        "SET NAMES utf8mb4;\n"
    )
    chunks.append(header)

    for tbl, cols, note in TABLES:
        chunks.append("")
        chunks.append("-- " + note)
        chunks.append(DDL_TEMPLATE[tbl])
        rows = fetch("SELECT %s FROM %s ORDER BY 1" % (",".join(cols), tbl))
        chunks.append("DELETE FROM %s;" % tbl)
        part = 1000
        for i in range(0, len(rows), part):
            batch = rows[i:i + part]
            rows_sql = ",".join(
                "(%s)" % ",".join(val(x) for x in r) for r in batch)
            chunks.append("INSERT INTO %s (%s) VALUES\n%s;" % (
                tbl, ",".join(cols), rows_sql))
        chunks.append("-- ^^^ %s 共 %d 行" % (tbl, len(rows)))

    apos = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(apos), exist_ok=True)
    with open(apos, "w", encoding="utf-8") as f:
        f.write("\n".join(chunks) + "\n")
    print("已生成 %s" % apos)
    print("大小 %.2f MB" % (os.path.getsize(apos) / 1024 / 1024))
    for tbl, cols, _ in TABLES:
        n = len(fetch("SELECT %s FROM %s" % (cols[0], tbl)))
        print("  %-22s %d 行" % (tbl, n))


if __name__ == "__main__":
    main()