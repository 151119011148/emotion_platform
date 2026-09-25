#!/bin/bash
# ============================================================================
# backfill.sh —— 按交易日清单逐个触发「一键拉取」(POST /api/review/fetch)
#
# 用途：给服务器补历史数据。对传入的每个交易日调用 fetch（T1-T8 编排），
#       自动把当日涨停池/指数/三池/行业快照/溢价/监管（含T-1/T-2回补）落库，
#       幂等（先删后插，重跑不重复）。
#
# 外部数据源限制：东财涨停池只回溯最近约 15 个交易日。超出窗口的日期
#   T2/T3/T4/T6 会 fail，但 T5(行业快照,读库内已有涨停池) 与 T7(监管) 仍可补，
#   且不会删除库内已存在的涨停池。
#
# 用法（4+ 个位置参数）：
#   bash backfill.sh <TOKEN> <LOG_FILE> <BASE_URL> <DATE...>
#     TOKEN    JWT 令牌
#     LOG_FILE 结果追加写到此文件
#     BASE_URL 后端地址，服务器本机 http://127.0.0.1:8080，公网 http://47.117.110.143
#     DATE...  要回补的交易日，形如 2026-09-23，可多个
#
# 完成判定：fetch 接口是 SSE，编排结束才 complete；脚本改为后台触发 fetch，
#   然后用 /fetch/status 轮询，直到出现 "task":"done"（编排结束标记）才算完成，
#   避免因 SSE 长连/curl 超时误判。每日期最多轮询 40 次 × 6s = 240s。
# ============================================================================
set -u

TOKEN="$1"; LOG="$2"; BASE="$3"; shift 3

if [ $# -eq 0 ]; then
  echo "用法: $0 <TOKEN> <LOG_FILE> <BASE_URL> <DATE...>" >&2
  exit 2
fi

echo "===== backfill start $(date '+%F %T') =====" >> "$LOG"

for d in "$@"; do
  # 1) 后台触发 fetch（请求送达即返回，避免阻塞等待 SSE 长连）
  curl -s -N --max-time 25 \
    -X POST "$BASE/api/review/fetch?date=$d" \
    -H "Authorization: Bearer $TOKEN" >/dev/null 2>&1 &
  local_cp=$!

  # 2) 轮询 status 直到编排结束：出现 task=done（正常收尾）或 overall 终态
  #    （SUCCESS/PARTIAL/FAILED 都可能，超窗外日期会 PARTIAL：T2/T3/T4/T6 失败、
  #    但 T5 行业快照与 T7 监管仍成功）。最多 60×5s=300s。
  result="NODONE"
  for _ in $(seq 1 60); do
    st=$(curl -s --max-time 8 \
      "$BASE/api/review/fetch/status?date=$d" \
      -H "Authorization: Bearer $TOKEN" 2>/dev/null)
    if echo "$st" | grep -qE '"task":"done"|"overall":"'; then
      result="DONE"
      break
    fi
    sleep 5
  done

  wait "$local_cp" 2>/dev/null
  echo "$(date '+%F %T')  $d  ->  $result" >> "$LOG"
done

echo "===== backfill end $(date '+%F %T') =====" >> "$LOG"