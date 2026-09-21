#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 readme 规范，把「海王星板块数据_待解析包」解析为两份可验收的 xlsx：
  1. 二级行业分类.xlsx  (Sheet1 二级行业汇总 / Sheet2 行业-个股明细)
  2. 题材板块.xlsx       (Sheet1 题材板块汇总 / Sheet2 题材-个股明细)
解析要点全部遵循 readme；仅一处修正：base.dbf 实测无“股票名称”字段，
股票名称改由项目库 t_market_stock 回填（覆盖不全处留空，代码/市场仍齐全）。
"""
import collections
import os
import subprocess

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

R = "emotion-server/src/main/resources/海王星板块数据_待解析包"
OUT = "emotion-server/src/main/resources/海王星板块数据_待解析包/generated"
os.makedirs(OUT, exist_ok=True)

HEAD_FILL = PatternFill("solid", fgColor="305496")
HEAD_FONT = Font(color="FFFFFF", bold=True)


def load_names():
    """项目库 t_market_stock 提供 code→name 回填。"""
    try:
        out = subprocess.run(
            ["mysql", "--protocol=tcp", "--host=127.0.0.1", "-u", "root",
             "-p123456", "-D", "emotion_dashboard", "-N",
             "-e", "SELECT DISTINCT code,name FROM t_market_stock"],
            capture_output=True, text=True, timeout=60).stdout
        return {ln.split("\t")[0]: ln.split("\t")[1].strip()
                for ln in out.splitlines() if "\t" in ln}
    except Exception as e:
        print("  (names via t_market_stock failed: %s)" % e)
        return {}


def read_tdxhy():
    """tdxhy.cfg → [(market,'0'|'1'|'2', code, t_code)] ；二级行业码= T+前4位数字。"""
    rows = []
    for l in open(os.path.join(R, "tdxhy.cfg"), encoding="gbk", errors="replace"):
        f = l.rstrip("\r\n").split("|")
        if len(f) < 3:
            continue
        mkt, code, tcode = f[0], f[1], f[2]
        if not code.isdigit():
            continue
        rows.append((mkt, code, tcode))
    return rows


def read_incon():
    """incon.dat #TDXNHY 段 → {行业码: 名称}。"""
    sec = None
    m = {}
    for l in open(os.path.join(R, "incon.dat"), encoding="gbk", errors="replace"):
        s = l.strip()
        if s.startswith("#"):
            sec = s.strip("#").strip()
            continue
        if sec == "TDXNHY" and "|" in s:
            c, n = s.split("|", 1)
            m[c.strip()] = n.strip()
    return m


def read_infoharbor():
    """infoharbor_block.dat → [(指数代码, 块名, [成分行])]，仅 GN_ (题材/概念) 块。
    块头一行 `#GN_名称,简称,指数代码,基日,更新,...`；其后若干「市场#代码,市场#代码,...」成分行。"""
    blocks, cur = [], None
    for l in open(os.path.join(R, "infoharbor_block.dat"), encoding="gbk",
                  errors="replace"):
        s = l.strip()
        if not s:
            continue
        if s.startswith("#"):
            head = [x.strip() for x in s[1:].split(",")]
            idx = head[2] if len(head) > 2 else ""
            if head[0].startswith("GN_"):
                cur = [idx, head[0], []]
                blocks.append(cur)
            else:
                cur = None                       # 只收 GN_ 块
        elif cur is not None:
            cur[2].extend(x for x in s.split(",") if x)
    return blocks


def read_tdxbk():
    """tdxbk.cfg → {(简称): (类别, 全称)}。"""
    m = {}
    for l in open(os.path.join(R, "tdxbk.cfg"), encoding="gbk", errors="replace"):
        f = l.rstrip().split("|")
        if len(f) >= 3:
            m[f[1]] = (f[0], f[2])
    return m


def norm_member(x):
    """'市场#代码'(0=深/北,1=沪) → (市场字母, 6位代码, 名称)；非法滤掉。"""
    if "#" not in x:
        return None
    m, code = x.split("#", 1)
    code = code.strip()
    if not (code.isdigit() and len(code) == 6):
        return None
    market = "深/北" if m == "0" else ("沪" if m == "1" else m)
    return market, code


def style(sheet, widths, freeze="A2"):
    for c, w in enumerate(widths, 1):
        sheet.column_dimensions[get_column_letter(c)].width = w
    for cell in sheet[1]:
        cell.fill = HEAD_FILL
        cell.font = HEAD_FONT
        cell.alignment = Alignment(vertical="center")
    sheet.freeze_panes = freeze


def write_sheet(wb, title, header, rows, widths):
    ws = wb.create_sheet(title)
    ws.append(header)
    for r in rows:
        ws.append(r)
    style(ws, widths)
    return ws


def _lv(code, digits):
    """行业码按长度取层级：T 后 digits 位（T01一级/ T0101二级/ T010101三级）。"""
    if not code.startswith("T"):
        return ""
    d = "".join(ch for ch in code if ch.isdigit())
    return ("T" + d[:digits]) if len(d) >= digits else ""


def _depth(code):
    return len([ch for ch in code if ch.isdigit()])


def build_industry(tdxhy, incon, names):
    """tdxhy 个股行按 T 码截断到二级(T+4)聚合；一级(T+2)作分组头；三级数由 incon 推算。"""
    market = {"0": "深", "1": "沪", "2": "北"}
    by_sec = collections.defaultdict(list)
    for mkt, code, tcode in tdxhy:
        sec = _lv(tcode, 4) or "__未分类__"
        by_sec[sec].append((market.get(mkt, mkt), code, names.get(code, "")))

    t3 = collections.Counter()          # 二级码 -> 三级行业数
    for c in incon:
        if _depth(c) == 6:
            t3[_lv(c, 4)] += 1

    summary = []
    for sec in sorted(by_sec.keys(), key=lambda s: (_lv(s, 2), s)):
        mem = by_sec[sec]
        sec_name = "" if sec == "__未分类__" else incon.get(sec, "")
        summary.append((_lv(sec, 2), incon.get(_lv(sec, 2), ""), sec, sec_name,
                        t3.get(sec, 0), len(mem), ", ".join(m[1] for m in mem)))

    detail = sorted(
        ((s, incon.get(s, ""), code, nm, mkt)
         for s, rows in by_sec.items() for mkt, code, nm in rows),
        key=lambda r: (r[0], r[2]))
    return summary, detail


def build_concept(infoharbor, tdxbk, names):
    summary, detail = [], []
    for idx, name, members in infoharbor:
        cn = name[3:] if name.startswith("GN_") else name
        mset = []
        for x in members:
            nm = norm_member(x)
            if nm:
                market, code = nm
                mset.append((market, code, names.get(code, "")))
        # 去重
        seen = set(); muniq = []
        for row in mset:
            if row[1] in seen:
                continue
            seen.add(row[1]); muniq.append(row)
        # GN_ 前缀=概念/题材；全称优先取 tdxbk，否则用板块名
        cat, full = tdxbk.get(cn, ("1", cn))
        summary.append((cat if cat else "1", cn, full if full else cn,
                        idx, len(muniq), ", ".join(r[1] for r in muniq)))
        for market, code, nm in muniq:
            detail.append((cn, code, nm, market))
    return summary, detail


def main():
    names = load_names()
    print("股票名称字典: %d 条(来自 t_market_stock)" % len(names))
    tdxhy = read_tdxhy()
    incon = read_incon()
    infoharbor = read_infoharbor()
    tdxbk = read_tdxbk()
    print("tdxhy 个股行 %d | #TDXNHY 行业 %d | GN_ 题材板块 %d | tdxbk 板块 %d"
          % (len(tdxhy), len(incon), len([b for b in infoharbor]),
             len(tdxbk)))

    # ---- 二级行业分类.xlsx ----
    ind_sum, ind_det = build_industry(tdxhy, incon, names)
    wb = openpyxl.Workbook(); wb.remove(wb.active)
    write_sheet(wb, "二级行业汇总",
                ["一级行业码", "一级行业名称", "二级行业码", "二级行业名称",
                 "三级行业数", "成分股数量", "成分股清单"],
                ind_sum, [10, 16, 12, 16, 12, 10, 60])
    write_sheet(wb, "行业-个股明细",
                ["二级行业码", "二级行业名称", "股票代码", "股票名称", "市场"],
                ind_det, [12, 18, 10, 14, 12])
    wb.save(os.path.join(OUT, "二级行业分类.xlsx"))
    print("二级行业：%d 个二级行业, %d 行个股明细" %
          (len(set(d[0] for d in ind_det)), len(ind_det)))

    # ---- 题材板块.xlsx ----
    con_sum, con_det = build_concept(infoharbor, tdxbk, names)
    wb = openpyxl.Workbook(); wb.remove(wb.active)
    write_sheet(wb, "题材板块汇总",
                ["板块类别", "板块简称", "板块全称", "板块指数代码",
                 "成分股数量", "成分股清单"],
                con_sum, [10, 18, 22, 12, 10, 80])
    write_sheet(wb, "题材-个股明细",
                ["板块名称", "股票代码", "股票名称", "市场"],
                con_det, [20, 10, 14, 12])
    wb.save(os.path.join(OUT, "题材板块.xlsx"))
    print("题材板块：%d 个(题材汇总), %d 行个股明细" %
          (len(con_sum), len(con_det)))

    print("输出目录:", os.path.abspath(OUT))


if __name__ == "__main__":
    main()