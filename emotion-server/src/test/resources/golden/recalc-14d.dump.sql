-- golden/recalc-14d.jsonl 的生成语句（一行一天，字段名与 RecalcGoldenTest 里的列名一一对应）。
--
-- 跑法：
--   M=/usr/local/mysql/bin/mysql
--   $M -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 -B -N -r emotion_dashboard \
--       < recalc-14d.dump.sql > recalc-14d.jsonl
--   （口令不写在这份文件里——它要进公开库；真值在未入库的 emotion-server/application-local.yml）
--
-- 两个必须留意的细节：
-- 1. 所有 DECIMAL 一律 CAST(... AS CHAR)。JSON 会把 30.70 打成 30.7，而 broken_note 里
--    印的是 BigDecimal.toString() —— "炸板率(次数) 30.70%"。少这一层 CAST，
--    基线会在每一天的 brokenNote 上假报警。
-- 2. -r（raw）不能省。默认的批处理模式会把中文和转义符再包一层，测试里就得自己解。
--
-- 换了账号或改了判据要重新 dump：基线的意义是"这份真实输入 → 这组结论"，
-- 输入过期了，断言就只是在保护一个已经不存在的世界。
--
-- 但判据刚改、库还没重算的那几天里，dump 出来的 expected 是旧结论，基线会自己把自己测红。
-- 那时候走另一条路：RecalcGoldenTest 的 bless 模式拿 fixture 里现成的输入重算一遍，
-- 只重写 expected 那一块，输入与列名一字不动。
--   mvn -o -B test -Dtest=RecalcGoldenTest -DfailIfNoSpecifiedTests=false \
--       -Dgolden.bless=src/test/resources/golden/recalc-14d.jsonl
-- bless 完必须接着把库重算（recalcAll），否则基线是引擎的、库是旧的，
-- 下一次 dump 会把结论倒回上一个口径。
SELECT JSON_OBJECT(
 'tradeDate', DATE_FORMAT(r.trade_date,'%Y-%m-%d'),
 'inputs', JSON_OBJECT('maxConsecutiveLimit',r.max_consecutive_limit,'limitUpCount',r.limit_up_count,
   'limitDownCount',r.limit_down_count,'yesterdayLimitPremium',CAST(r.yesterday_limit_premium AS CHAR),
   'brokenBoardRate',CAST(r.broken_board_rate AS CHAR),'bigLossCount',r.big_loss_count,
   'totalVolume',CAST(r.total_volume AS CHAR),'scoreTheme',r.score_theme),
 'poolCounts', JSON_OBJECT('ztCount',p.zt,'zbCount',p.zb,'resealCount',p.reseal),
 'tiers', (SELECT IFNULL(JSON_ARRAYAGG(JSON_OBJECT('board',t.board,'stockCount',t.stock_count,'matched',t.matched,
            'avgPct',CAST(t.avg_pct AS CHAR),'maxPct',CAST(t.max_pct AS CHAR),'minPct',CAST(t.min_pct AS CHAR))), CAST(NULL AS JSON))
           FROM t_premium_tier t WHERE t.trade_date=r.trade_date),
 'anchorScore',r.anchor_score,'anchorNote',r.anchor_note,
 'survCount',r.surv_count,'survPremium',CAST(r.surv_premium AS CHAR),'survNote',r.surv_note,
 'expected', JSON_OBJECT('scoreHeight',r.score_height,'scorePremium',r.score_premium,'scoreBreadth',r.score_breadth,
   'scoreBroken',r.score_broken,'scoreLoss',r.score_loss,'scoreVolume',r.score_volume,'scoreTheme',r.score_theme,
   'anchorScore',r.anchor_score,'anchorNote',r.anchor_note,'survCount',r.surv_count,
   'survPremium',CAST(r.surv_premium AS CHAR),'survNote',r.surv_note,
   'sealedHomeRate',CAST(r.sealed_home_rate AS CHAR),'resealRate',CAST(r.reseal_rate AS CHAR),
   'brokenNote',r.broken_note,'premiumWeighted',CAST(r.premium_weighted AS CHAR),
   'totalScore',r.total_score,'temperature',CAST(r.temperature AS CHAR),
   'prevTemperature',CAST(r.prev_temperature AS CHAR),'scoredDims',r.scored_dims,'stage',r.stage,
   'stageDirection',r.stage_direction,'stageSeq',r.stage_seq,'stagePhase',r.stage_phase)
) j
FROM t_daily_record r
LEFT JOIN (SELECT trade_date, IFNULL(SUM(pool='ZT'),0) zt, IFNULL(SUM(pool='ZB'),0) zb,
                  IFNULL(SUM(pool='ZT' AND IFNULL(break_count,0)>0),0) reseal
           FROM t_market_stock GROUP BY trade_date) p ON p.trade_date=r.trade_date
WHERE r.user_id=2 ORDER BY r.trade_date;
