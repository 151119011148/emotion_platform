<template>
  <div class="indicator-cards">
    <div class="card" v-for="item in indicators" :key="item.key">
      <el-tooltip
        placement="bottom"
        :show-after="150"
        popper-class="card-tip"
      >
        <template #content>
          <div class="tip">
            <div class="tip-score">{{ item.scoreLine }}</div>
            <template v-if="item.lines || item.note">
              <div class="tip-title">{{ item.tipTitle }}</div>
              <div v-for="(line, i) in item.lines" :key="i" class="tip-line">
                <span class="tip-main">{{ line.main }}</span>
                <span class="tip-sub">{{ line.sub }}</span>
              </div>
              <div v-if="item.note" class="tip-note">{{ item.note }}</div>
            </template>
          </div>
        </template>
        <div class="card-inner hoverable">
          <div class="card-label">{{ item.label }}</div>
          <div class="card-value">{{ item.value }}</div>
          <div class="card-trend" :class="item.trend">
            <span v-if="item.trend === 'up'">↑</span>
            <span v-else-if="item.trend === 'down'">↓</span>
            <span v-else-if="item.unscored" class="unscored">未评</span>
          </div>
          <div class="card-score">
            <span v-if="item.score < 0" class="minus">{{ item.score }}</span>
            <template v-else>
              <span v-for="n in 3" :key="n" class="dot" :class="{ active: n <= item.score }"></span>
            </template>
          </div>
        </div>
      </el-tooltip>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CARD_ORDER, survivalBandOf, signed, dimScoreLine } from '../utils/scores'

const props = defineProps({
  record: { type: Object, default: null },
  /** 该交易日的盘面明细（/api/market/stocks），没回补过就是 null */
  details: { type: Object, default: null },
  /** 该交易日的逐档溢价（/api/market/premium-tiers），没回补过就是 null */
  tiers: { type: Object, default: null },
  /** 该交易日的阵眼（/api/anchors），卡片只借它的当日涨跌 */
  anchor: { type: Object, default: null }
})

/** 名单超过这么长就截断：tooltip 是给人扫一眼的，不是给人翻页的。 */
const MAX_LINES = 15

/**
 * 卡面标题只有这一份（带单位，是给这一屏看的短名）；<b>顺序不在这里</b>，
 * 那份在 {@code utils/scores} 的 CARD_ORDER，复盘页九维用的是同一个数组。
 * tooltip 首行那个「第 N 维」才是引擎维序——它是这张卡和复盘页同一行对得上的编号，
 * 所以扫过去编号会跳（6、3、1、2…），那是刻意的。
 */
const CARD_LABELS = {
  volume: '成交额(亿)',
  breadth: '涨停/跌停',
  height: '连板高度',
  premium: '分档溢价',
  surv: '异动监管',
  theme: '主线明确度',
  anchor: '阵眼当日',
  loss: '大面数',
  broken: '炸板率'
}
const CARDS = CARD_ORDER.map((key) => ({ key, label: CARD_LABELS[key] }))

function stockNames(list, limit = MAX_LINES) {
  const shown = list.slice(0, limit).map((s) => s.name)
  return { text: shown.join('、'), hidden: list.length - shown.length }
}

function ladderLines(ladder, firstBoardCount) {
  const lines = ladder.map((tier) => ({
    main: `${tier.board} 板 · ${tier.stocks.length} 家`,
    sub: stockNames(tier.stocks, 8).text
  }))
  if (firstBoardCount) {
    lines.push({ main: `首板 · ${firstBoardCount} 家`, sub: '' })
  }
  return lines
}

function detailTips(details) {
  if (!details || !details.available) return {}
  const tips = {}

  if (details.ladder?.length) {
    const hidden = details.ladder.reduce(
      (acc, tier) => acc + Math.max(0, tier.stocks.length - 8), 0)
    tips.height = {
      title: '连板梯队',
      lines: ladderLines(details.ladder, details.firstBoardCount),
      note: details.gapBoards?.length
        ? `${details.gapBoards.join('、')} 板断档，最高板是独苗` + (hidden ? `；另有 ${hidden} 只未列出` : '')
        : (hidden ? `另有 ${hidden} 只未列出` : '')
    }
  }

  if (details.limitDown?.length) {
    const list = details.limitDown
    const hidden = list.length - Math.min(list.length, MAX_LINES)
    tips.breadth = {
      title: `跌停 ${list.length} 家`,
      lines: list.slice(0, MAX_LINES).map((s) => ({
        main: s.name, sub: `${s.pct}%${s.industry ? ' · ' + s.industry : ''}`
      })),
      note: hidden > 0 ? `另有 ${hidden} 家未列出` : ''
    }
  }

  if (details.bigLoss?.length) {
    const list = details.bigLoss
    const hidden = list.length - Math.min(list.length, MAX_LINES)
    tips.loss = {
      title: `大面 ${list.length} 家（自涨停回撤 >7% 且收盘绿盘）`,
      lines: list.slice(0, MAX_LINES).map((s) => ({
        main: s.name, sub: `回撤 ${s.pullback}%${s.industry ? ' · ' + s.industry : ''}`
      })),
      note: hidden > 0 ? `另有 ${hidden} 只未列出` : ''
    }
  }
  return tips
}

/**
 * 溢价卡：逐档 2..8+ 是展示粒度，进分只看低/中/高三组。
 * 断档那一组要写出来——"高位没人"本身就是信号，藏起来等于把断层说成没数据。
 */
function premiumTip(tiers, r) {
  if (!tiers || !tiers.available) return {}
  // 三组的边界是活的（按前一天最高板的一半定），所以逐档那几行要顺手标出它落在哪一组
  const shortGroup = {}
  for (const g of tiers.groups || []) shortGroup[g.group] = (g.label || '').split('(')[0]
  // 数组顺序（逐档从高到低、三组高位在前）由后端排好，这里不再排一次
  const lines = (tiers.tiers || []).map((t) => ({
    main: `${t.label} 板`,
    sub: `${signed(t.avgPct)}% · ${t.matched}/${t.stockCount} 只${shortGroup[t.group] ? ` · ${shortGroup[t.group]}` : ''}`
  }))
  for (const g of tiers.groups || []) {
    lines.push({
      main: g.label,
      sub: g.score == null
        ? '断档 · 权重摊给其余档'
        : `${signed(g.avgPct)}% → ${g.score} 分 ×${g.weight}`
    })
  }
  const first = r?.yesterdayLimitPremium
  return {
    premium: {
      title: `昨日涨停池（${tiers.prevTradeDate || '前一日'}）· 首板 ${tiers.firstBoard ?? 0} 只不计`,
      lines,
      note: [
        tiers.binNote,
        `三组加权合成 ${signed(tiers.weightedPct)}% 进分${first == null ? '' : `；含首板整体 ${signed(first)}% 只展示不打分`}`,
        `结构：${tiers.structure || '—'}`
      ].filter(Boolean).join('；')
    }
  }
}

/**
 * 炸板率卡。第 4 维现在是三个子项取平均，卡片上那一个百分数解释不了另外两支，
 * 所以三个率各摆一行、子分怎么来的读后端落库的那条算式。
 * 家数两个率没回补过盘面明细就是 null——那是"没取到"，不是"封板率 0%"。
 */
function brokenTip(r) {
  if (!r) return {}
  const { brokenBoardRate: rate, sealedHomeRate: sealed, resealRate: reseal } = r
  if (rate == null && sealed == null && reseal == null) return {}
  const text = (v) => (v == null ? '盘面明细未取到' : `${v}%`)
  return {
    broken: {
      title: '第 4 维 · 三分支各出分再平均',
      lines: [
        { main: '炸板率(次数)', sub: text(rate) },
        { main: '家数封板率', sub: text(sealed) },
        { main: '回封率', sub: text(reseal) }
      ],
      note: [
        r.brokenNote,
        '炸板率数的是打开次数（一只票炸三次算三次），100% 减它不等于家数封板率'
      ].filter(Boolean).join('；')
    }
  }
}

/** 阵眼卡：值用当日涨跌，判据用后端存下来的那条中文串。 */
function anchorTip(anchor, r) {
  const items = anchor?.items || []
  if (!items.length) {
    return { anchor: { title: '周期阵眼', lines: null, note: r?.anchorNote || anchor?.note || '' } }
  }
  return {
    anchor: {
      title: `在位 ${items.length} 只 · 第 8 维取最差`,
      lines: items.map((it) => ({
        main: it.name,
        sub: `${signed(it.pct)}% · ${it.score == null ? '未评' : it.score + ' 分'}`
      })),
      note: r?.anchorNote || ''
    }
  }
}

function survTip(r) {
  return { surv: { title: '异动监管 · 今日进分溢价', lines: null, note: r?.survNote || '' } }
}

function themeTip(r) {
  const labels = {
    3: '有清晰主线 + 龙头',
    2: '有主线但龙头不明确',
    1: '有热点无主线',
    0: '无主线',
    '-1': '热点散乱',
    '-2': '无明显热点',
    '-3': '全面退潮'
  }
  const parts = []
  if (r?.mainTheme) parts.push(`主线：${r.mainTheme}`)
  if (r?.scoreTheme != null) parts.push(labels[r.scoreTheme] || '')
  return {
    theme: {
      title: '第 7 维 · 人工判断',
      lines: null,
      note: parts.filter(Boolean).join('；') || '未评'
    }
  }
}

/** 多只在位时和后端同一套：分低的那只说话，分一样看谁跌得深。 */
function worstAnchor(items) {
  let worst = null
  for (const it of items) {
    if (it.pct == null) continue
    if (!worst || Number(it.pct) < Number(worst.pct)) worst = it
  }
  return worst
}

const indicators = computed(() => {
  const r = props.record
  const tips = {
    ...detailTips(props.details),
    ...premiumTip(props.tiers, r),
    ...brokenTip(r),
    ...anchorTip(props.anchor, r),
    ...survTip(r),
    ...themeTip(r)
  }
  const card = (key, extra) => ({
    key, label: CARD_LABELS[key], trend: '', score: 0, unscored: false, raw: null,
    tipTitle: tips[key]?.title, lines: tips[key]?.lines, note: tips[key]?.note,
    ...extra
  })
  const withScoreLine = (item) => ({ ...item, scoreLine: dimScoreLine(item.key, item.raw) })

  if (!r) {
    return CARDS.map((c) => card(c.key, { value: '--' })).map(withScoreLine)
  }

  const scored = (value) => ({ score: value ?? 0, unscored: value == null, raw: value ?? null })
  const sign = (v) => (Number(v) > 0 ? 'up' : Number(v) < 0 ? 'down' : '')
  const anchorItem = worstAnchor(props.anchor?.items || [])
  // 第 9 维的分没有单独入库（它由 surv_premium 现算），走接力那套五档，不是溢价七档
  const survScore = r.survCount > 0 ? survivalBandOf(r.survPremium) : null

  // 出卡顺序由 CARDS 排，这里只写每一维的读数
  const byKey = {
    height: card('height', {
      value: r.maxConsecutiveLimit ?? '--', ...scored(r.scoreHeight)
    }),
    premium: card('premium', {
      value: r.premiumWeighted != null ? `${r.premiumWeighted}%`
        : (r.yesterdayLimitPremium != null ? `${r.yesterdayLimitPremium}%` : '--'),
      trend: sign(r.premiumWeighted ?? r.yesterdayLimitPremium),
      ...scored(r.scorePremium)
    }),
    breadth: card('breadth', {
      value: `${r.limitUpCount ?? 0} / ${r.limitDownCount ?? 0}`, ...scored(r.scoreBreadth)
    }),
    broken: card('broken', {
      value: r.brokenBoardRate != null ? `${r.brokenBoardRate}%` : '--',
      trend: Number(r.brokenBoardRate) < 30 ? 'up' : Number(r.brokenBoardRate) > 50 ? 'down' : '',
      ...scored(r.scoreBroken)
    }),
    loss: card('loss', {
      value: r.bigLossCount ?? '--',
      trend: (r.bigLossCount ?? 0) <= 1 ? 'up' : (r.bigLossCount ?? 0) > 4 ? 'down' : '',
      ...scored(r.scoreLoss)
    }),
    volume: card('volume', {
      value: r.totalVolume ?? '--', ...scored(r.scoreVolume)
    }),
    theme: card('theme', {
      value: r.scoreTheme != null ? r.scoreTheme : '--',
      ...scored(r.scoreTheme)
    }),
    anchor: card('anchor', {
      value: anchorItem ? `${signed(anchorItem.pct)}%` : (r.anchorScore == null ? '未设' : '无行情'),
      trend: sign(anchorItem?.pct),
      ...scored(r.anchorScore)
    }),
    surv: card('surv', {
      value: r.survPremium != null ? `${r.survPremium}%`
        : (r.survCount === 0 ? '无在列' : '--'),
      trend: r.survCount > 0 ? sign(r.survPremium) : '',
      ...scored(survScore)
    })
  }
  return CARDS.map((c) => withScoreLine(byKey[c.key]))
})
</script>

<style scoped>
.indicator-cards {
  display: grid;
  /* 9 个 1fr 在窄容器下每格只剩几 px，用 auto-fit 兜住最小可读宽度 */
  grid-template-columns: repeat(auto-fit, minmax(min(130px, 100%), 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.card {
  background: #1a2332;
  border-radius: 10px;
  padding: 16px;
  text-align: center;
}
.card-inner {
  text-align: center;
}
.card-inner.hoverable {
  cursor: default;
}
.card-label {
  font-size: 12px;
  color: #8899a6;
  margin-bottom: 8px;
}
.card-value {
  font-size: 22px;
  font-weight: 700;
  color: #e1e8ed;
  margin-bottom: 6px;
  /* 长数字（20306.68）和"无在列"这种三字文案都不许把卡片撑开 */
  overflow-wrap: anywhere;
}
.card-trend {
  font-size: 16px;
  line-height: 16px;
  margin-bottom: 8px;
}
.card-trend.up { color: #ef4444; }
.card-trend.down { color: #3b82f6; }
.unscored {
  font-size: 11px;
  color: #8899a6;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 1px 4px;
}
.card-score {
  display: flex;
  justify-content: center;
  gap: 4px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2d3748;
}
.dot.active {
  background: #f59e0b;
}
/* 负分：三颗空心和 0 分撞脸，只能另给一个标记。蓝色沿用全站"跌/坏"那一侧。 */
.minus {
  font-size: 12px;
  font-weight: 700;
  line-height: 14px;
  padding: 0 6px;
  color: #60a5fa;
  border: 1px solid #1e40af;
  border-radius: 4px;
}
</style>

<style>
/* popper 挂在 body 下，scoped 选择器到不了，所以这段故意不加 scoped */
.card-tip {
  max-width: 340px;
}
.card-tip .tip-score {
  font-weight: 700;
  /* 和卡片上那颗进分点同色：一眼对得上"这就是这一维的那几分" */
  color: #f59e0b;
  margin-bottom: 8px;
}
.card-tip .tip-title {
  font-weight: 700;
  margin-bottom: 6px;
}
.card-tip .tip-line {
  display: flex;
  gap: 8px;
  line-height: 1.6;
}
.card-tip .tip-main {
  flex: 0 0 auto;
  min-width: 74px;
  text-align: left;
}
.card-tip .tip-sub {
  color: #8899a6;
  text-align: left;
}
.card-tip .tip-note {
  margin-top: 6px;
  color: #d97706;
}
</style>
