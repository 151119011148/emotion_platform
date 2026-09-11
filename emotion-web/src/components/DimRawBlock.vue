<template>
  <section class="raw-dim" v-loading="loading">
    <div class="dim-title"><h3>{{ title }}</h3></div>

    <!-- ============ D1 大盘生态：1:1 复刻 MarketView 原始读数区（不含打分表） ============ -->
    <template v-if="dimKey === 'market'">
      <div class="block">
        <div class="block-head">
          <h4 class="blk-h4">五大指数 <span class="sub" v-if="idxTradeDate">{{ idxTradeDate }} 收盘</span></h4>
        </div>
        <el-empty v-if="!indexes.length && !loading" description="当日没有指数收盘数据（未导入或未回补）" :image-size="60" />
        <div v-else class="index-grid">
          <div v-for="ix in indexes" :key="ix.code" class="index-card">
            <span class="ix-name">{{ ix.name }}</span>
            <span class="ix-code">{{ ix.code }}</span>
            <span class="ix-close">{{ ix.close == null ? '—' : Number(ix.close).toFixed(2) }}</span>
            <span class="ix-pct" :class="pctClass(ix.changePct)">
              {{ ix.changePct == null ? '—' : signed(ix.changePct) + '%' }}
            </span>
          </div>
        </div>
      </div>

      <div class="stat-grid">
        <div class="stat">
          <span class="stat-label">两市成交额</span>
          <span class="stat-value">{{ record?.totalVolume != null ? Number(record.totalVolume).toLocaleString() + ' 亿' : '—' }}</span>
          <span class="stat-sub">复盘导入口径</span>
        </div>
        <div class="stat">
          <span class="stat-label">量比（成交额 / 20 日均）</span>
          <span class="stat-value">{{ ratioText(metrics.turnover_ratio) }}</span>
          <span class="stat-sub">{{ turnoverBand || '量能档未评' }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">上涨 / 下跌家数</span>
          <span class="stat-value">
            <span class="up">{{ breadthText.up }}</span>
            <span class="sep">/</span>
            <span class="down">{{ breadthText.down }}</span>
          </span>
          <span class="stat-sub">{{ breadthText.sub }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">涨停 / 跌停</span>
          <span class="stat-value">
            <span class="up">{{ metrics.limit_up_count ?? '—' }}</span>
            <span class="sep">/</span>
            <span class="down">{{ metrics.limit_down_count ?? '—' }}</span>
          </span>
          <span class="stat-sub">家</span>
        </div>
      </div>
    </template>

    <!-- ============ D3 连板生态：1:1 复刻 TiantiView 顶部 4 张原始卡 ============ -->
    <div v-else-if="dimKey === 'board'" class="stat-grid">
      <div class="stat">
        <span class="stat-label">最高连板 H</span>
        <span class="stat-value">{{ nz(tt?.maxBoard) }}</span>
        <span class="stat-sub">全市场</span>
      </div>
      <div class="stat">
        <span class="stat-label">日内核心</span>
        <template v-if="tt?.mainIndustry">
          <span class="stat-value main-industry">{{ tt.mainIndustry }}</span>
          <el-tag size="small" :type="tt.mainlineConfirmed ? 'success' : 'warning'">
            {{ tt.mainlineConfirmed ? '已成主线' : `热度${nz(tt.persistenceDays)}天未成主线` }}
          </el-tag>
        </template>
        <span v-else class="stat-sub">—</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停 / 炸板</span>
        <span class="stat-value">{{ nz(tt?.ztTotal) }} / {{ nz(tt?.zbTotal) }}</span>
        <span class="stat-sub">当日家数</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停聚集</span>
        <span class="stat-value">{{ pctText(tt?.ztGatherPct) }}</span>
        <span class="stat-sub">高度聚集 {{ pctText(tt?.heightGatherPct) }}</span>
      </div>
    </div>

    <!-- ============ D4 首板生态：1:1 复刻 ShoubanView 顶部 6 张原始卡 ============ -->
    <div v-else-if="dimKey === 'first'" class="stat-grid">
      <div class="stat">
        <span class="stat-label">首板封住</span>
        <span class="stat-value">{{ sbSummary ? nz(sbSummary.sealedCount) : '—' }}</span>
        <span class="stat-sub">涨停池 1 板</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板炸板</span>
        <span class="stat-value">{{ sbSummary ? nz(sbSummary.bombedCount) : '—' }}</span>
        <span class="stat-sub">{{ sbPrevReady ? '可判定口径' : '昨日明细缺失' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">封板率</span>
        <span class="stat-value">{{ pctText(sbSummary?.sealedRate) }}</span>
        <span class="stat-sub">封住 ÷（封住 + 首板炸板）</span>
      </div>
      <div class="stat">
        <span class="stat-label">昨日首板</span>
        <span class="stat-value">{{ sbSummary ? nz(sbSummary.prevFirstCount) : '—' }}</span>
        <span class="stat-sub">{{ sbPrevDate || '—' }} 首板</span>
      </div>
      <div class="stat">
        <span class="stat-label">1 进 2 晋级率</span>
        <span class="stat-value">{{ sbPromoText }}</span>
        <span class="stat-sub">{{ sbSummary?.prevFirstCount ? `晋级 ${nz(sbSummary.promoCount)} 只` : '不可算' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">昨首板今均溢价</span>
        <span class="stat-value" :class="pctClass(sbSummary?.prevFirstPremiumPct)">
          {{ sbSummary?.prevFirstPremiumPct == null ? '未落档' : signed(sbSummary.prevFirstPremiumPct) + '%' }}
        </span>
        <span class="stat-sub">档位表 board=1 全样本</span>
      </div>
    </div>

    <!-- ============ D2 主线明确度：1:1 复刻 MainlineView 日内核心五要素 ============ -->
    <div v-else-if="dimKey === 'theme_main'" class="block">
      <div class="block-head">
        <h4 class="blk-h4">日内核心五要素 <span class="sub">{{ ml?.mainIndustry || '—' }}</span></h4>
        <span class="scale">核心涨停 {{ nz(ml?.mainZt) }}/{{ nz(ml?.ztTotal) }} · 核心最高板 {{ nz(ml?.mainMaxBoard) }}/{{ nz(ml?.maxBoard) }}</span>
      </div>
      <el-empty v-if="!ml && !loading" description="当日无日内核心：行情明细未回补，或当日没有涨停股" :image-size="60" />
      <div v-else class="elem-grid">
        <div class="elem">
          <span class="elem-label">涨停聚集度</span>
          <span class="elem-value">{{ pctText(ml?.ztGatherPct) }}</span>
          <span class="elem-sub">自动 · 权重 25%</span>
        </div>
        <div class="elem">
          <span class="elem-label">高度聚集度</span>
          <span class="elem-value">{{ pctText(ml?.heightGatherPct) }}</span>
          <span class="elem-sub">自动 · 权重 25%</span>
        </div>
        <div class="elem">
          <span class="elem-label">成交额聚集度</span>
          <span class="elem-value">{{ pctText(ml?.amountGatherPct) }}</span>
          <span class="elem-sub">人工口径{{ ml?.amountGatherPct == null ? ' · 未填=未评' : '' }}</span>
        </div>
        <div class="elem">
          <span class="elem-label">催化剂硬度</span>
          <span class="elem-value">
            <el-rate v-if="ml?.catalystHardness != null" :model-value="ml.catalystHardness" disabled
              text-color="#fbbf24" style="--el-rate-icon-margin: 1px" />
            <span v-else class="missing">未评</span>
          </span>
          <span class="elem-sub">题材登记 · 权重 15%</span>
        </div>
        <div class="elem">
          <span class="elem-label">持续性</span>
          <span class="elem-value">{{ ml?.persistenceDays != null ? ml.persistenceDays + ' 天' : '—' }}</span>
          <span class="elem-sub">连续热度 · 权重 15%（≥5家/日）</span>
        </div>
      </div>
    </div>

    <!-- ============ D5 阵眼：1:1 复刻 MainlineView 龙头分工原始读数 ============ -->
    <div v-else-if="dimKey === 'anchor'" class="block">
      <div class="block-head">
        <h4 class="blk-h4">龙头分工</h4>
        <span class="scale">总龙头 50% · 中军 20% · 跟风 15% · 卡位 10% · 反包 5%</span>
      </div>
      <el-empty v-if="!ml && !loading" description="当日无阵眼数据：行情明细未回补" :image-size="60" />
      <template v-else>
        <div class="dragon-card" v-if="ml?.dragon">
          <div class="dragon-line">
            <el-tag type="danger" effect="dark" size="small">总龙头</el-tag>
            <span class="dragon-name">{{ ml.dragon.name || '—' }}</span>
            <span class="dragon-code">{{ ml.dragon.code }}</span>
            <el-tag size="small" effect="plain">{{ ml.dragon.industry || '行业未登记' }}</el-tag>
            <span class="dragon-board">{{ nz(ml.dragon.board) }} 板</span>
            <span :class="pctClass(ml.dragon.changePct)">
              {{ ml.dragon.changePct == null ? '—' : signed(ml.dragon.changePct) + '%' }}
            </span>
            <el-tag :type="ACTION_TYPE[ml.dragon.action] || 'info'" size="small">
              {{ ACTION_LABEL[ml.dragon.action] || ml.dragon.action || '—' }}
            </el-tag>
            <span v-if="ml.dragon.pullbackPct != null" class="pullback">
              自涨停回撤 -{{ Number(ml.dragon.pullbackPct).toFixed(2) }}%
            </span>
          </div>
          <p v-if="ml.dragon.reason" class="dragon-reason">判定依据：{{ ml.dragon.reason }}</p>
        </div>
        <el-alert v-else type="info" :closable="false" show-icon title="当日无总龙头（全市场没有连板股），阵眼按缺判" style="margin-bottom: 14px" />

        <div class="stat-grid">
          <div class="stat">
            <span class="stat-label">中军</span>
            <span class="stat-value">{{ ml?.zhongJun?.length ?? '—' }}</span>
            <span class="stat-sub">日内核心内其余连板 ≥2</span>
          </div>
          <div class="stat">
            <span class="stat-label">跟风</span>
            <span class="stat-value">{{ nz(ml?.genFengCount) }}</span>
            <span class="stat-sub">日内核心内涨停家数</span>
          </div>
          <div class="stat">
            <span class="stat-label">卡位</span>
            <span class="stat-value kw">{{ ml?.kaWei?.name || '—' }}</span>
            <span class="stat-sub">
              <template v-if="ml?.kaWei">{{ ml.kaWei.sealed === false ? '今日炸板' : '封住' }} · {{ nz(ml.kaWei.board) }} 板</template>
              <template v-else>他题材高标缺席</template>
            </span>
          </div>
          <div class="stat">
            <span class="stat-label">反包</span>
            <span class="stat-value">{{ ml?.fanBao?.length ?? '—' }}</span>
            <span class="stat-sub">昨炸板今回封家数</span>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { marketApi, recordApi, prdApi } from '../api/modules'
import { useScoringStore } from '../stores/scoring'
import { signed } from '../utils/scores'

const props = defineProps({
  date: { type: String, required: true },
  dimKey: { type: String, required: true },
  title: { type: String, required: true }
})

const scoring = useScoringStore()
const loading = ref(false)

const ACTION_LABEL = { PROMOTE: '晋级', HOLD: '在位', BREAK: '断板', ABSENT: '缺席' }
const ACTION_TYPE = { PROMOTE: 'success', HOLD: 'primary', BREAK: 'danger', ABSENT: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')

/* 各维原始 VO */
const indexes = ref([])
const idxTradeDate = ref('')
const record = ref(null)
const liveBreadth = ref(null)
const tt = ref(null)   // TiantiVO
const sb = ref(null)   // ShoubanVO
const ml = ref(null)   // MainlineVO（D2/D5 共用）

/**
 * 模块级请求共享：复盘页同屏 D2、D5 都要 mainline，D1 要 record。
 * key 带日期，切日期天然换 key；失败即删缓存，允许下次重试。
 */
const cache = new Map()
function cached(key, factory) {
  if (!cache.has(key)) {
    const p = factory().catch((e) => { cache.delete(key); throw e })
    cache.set(key, p)
  }
  return cache.get(key)
}
const idxKey = (d) => `idx:${d}`
const recKey = (d) => `rec:${d}`
const ttKey = (d) => `tt:${d}`
const sbKey = (d) => `sb:${d}`
const mlKey = (d) => `ml:${d}`

/* score-detail metrics：D1 量比 / 涨跌停从这里读（拉取行情后 scoring 会 force 刷新，本组件响应式跟随） */
const metrics = computed(() => scoring.detail?.metrics || {})
const turnoverBand = computed(() => {
  const sub = (scoring.detail?.dims || []).find((d) => d.key === 'market')
    ?.children?.find((c) => c.key === 'turnover')
  return sub?.bandHit || ''
})

const isLatestDay = computed(() => props.date === todayStr)
const breadthText = computed(() => {
  const r = record.value
  if (r?.upCount != null && r?.downCount != null) {
    const denom = r.upCount + r.downCount
    const ratio = denom ? ((r.upCount / denom) * 100).toFixed(1) + '%' : '—'
    return { up: r.upCount, down: r.downCount, sub: '复盘导入 · 红盘率 ' + ratio }
  }
  if (isLatestDay.value && liveBreadth.value) {
    return {
      up: liveBreadth.value.upCount,
      down: liveBreadth.value.downCount,
      sub: '实时 · 红盘率 ' + (liveBreadth.value.redRatioPct ?? '—') + '%' +
        (liveBreadth.value.flatCount != null ? ' · 平 ' + liveBreadth.value.flatCount : '')
    }
  }
  return { up: '—', down: '—', sub: isLatestDay.value ? '实时未取到' : '历史日无导入数据' }
})

const sbSummary = computed(() => sb.value?.summary || null)
const sbPrevReady = computed(() => sb.value?.prevAvailable === true)
const sbPrevDate = computed(() => sb.value?.prevDate || '')
const sbPromoText = computed(() => {
  const s = sbSummary.value
  if (!s || s.prevFirstCount === 0) return '不可算'
  return s.promoRate == null ? '—' : s.promoRate.toFixed(1) + '%'
})

function nz(v) { return v == null ? '—' : v }
function pctText(v) { return v == null ? '—' : Number(v).toFixed(1) + '%' }
function ratioText(v) { return v == null ? '—' : Number(v).toFixed(2) }
function pctClass(p) {
  if (p == null || Number.isNaN(Number(p))) return ''
  return Number(p) > 0 ? 'up' : Number(p) < 0 ? 'down' : ''
}

async function load() {
  const d = props.date
  if (!d) return
  loading.value = true
  try {
    const jobs = [scoring.loadDetail(d, true)]
    if (props.dimKey === 'market') {
      jobs.push(
        cached(idxKey(d), () => marketApi.indexes(d)).then((res) => { indexes.value = res?.data?.indexes || []; idxTradeDate.value = res?.data?.tradeDate || '' }).catch(() => {}),
        cached(recKey(d), () => recordApi.getByDate(d)).then((res) => { record.value = res?.data || null }).catch(() => { record.value = null })
      )
      if (d === todayStr) {
        jobs.push(marketApi.breadth().then((res) => { liveBreadth.value = res?.data || null }).catch(() => { liveBreadth.value = null }))
      } else {
        liveBreadth.value = null
      }
    } else if (props.dimKey === 'board') {
      jobs.push(cached(ttKey(d), () => prdApi.tianti(d)).then((res) => { tt.value = res?.data || null }).catch(() => { tt.value = null }))
    } else if (props.dimKey === 'first') {
      jobs.push(cached(sbKey(d), () => prdApi.shouban(d)).then((res) => { sb.value = res?.data || null }).catch(() => { sb.value = null }))
    } else if (props.dimKey === 'theme_main' || props.dimKey === 'anchor') {
      jobs.push(cached(mlKey(d), () => prdApi.mainline(d)).then((res) => { ml.value = res?.data || null }).catch(() => { ml.value = null }))
    }
    await Promise.all(jobs)
  } finally {
    loading.value = false
  }
}

watch(() => props.date, load)
onMounted(load)
</script>

<style scoped>
.raw-dim {
  margin-bottom: 20px;
}
.dim-title h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: #e1e8ed;
}
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.blk-h4 {
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
  font-weight: 600;
}
.sub {
  font-size: 12px;
  color: #8899a6;
  font-weight: 400;
  margin-left: 6px;
}
.scale {
  font-size: 12px;
  color: #6b7c8c;
}

/* 五大指数 */
.index-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(170px, 100%), 1fr));
  gap: 12px;
}
.index-card {
  background: #0f1419;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ix-name { font-size: 14px; color: #e1e8ed; font-weight: 600; }
.ix-code { font-size: 11px; color: #6b7c8c; }
.ix-close { font-size: 20px; font-weight: 700; color: #e1e8ed; }
.ix-pct { font-size: 14px; font-weight: 700; }

/* 通用 stat 卡（D1/D3/D4/D5） */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
  gap: 12px;
}
.stat {
  background: #1a2332;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label { font-size: 12px; color: #8899a6; }
.stat-value { font-size: 20px; font-weight: 700; color: #e1e8ed; }
.stat-value.main-industry { font-size: 17px; }
.stat-value.kw { font-size: 17px; }
.stat-sub { font-size: 11px; color: #8899a6; }
.sep { color: #5b6c7d; margin: 0 6px; }
.up { color: #ef4444; }
.down { color: #3b82f6; }
.missing { font-size: 13px; color: #6b7c8c; }

/* D2 五要素 */
.elem-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 12px;
}
.elem {
  background: #0f1419;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.elem-label { font-size: 12px; color: #8899a6; }
.elem-value { font-size: 20px; font-weight: 700; color: #e1e8ed; min-height: 26px; }
.elem-sub { font-size: 11px; color: #6b7c8c; }

/* D5 龙头 */
.dragon-card {
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.dragon-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dragon-name { font-size: 15px; font-weight: 700; color: #e1e8ed; }
.dragon-code { font-size: 12px; color: #6b7c8c; }
.dragon-board { font-size: 13px; color: #fbbf24; font-weight: 600; }
.pullback { font-size: 12px; color: #f87171; }
.dragon-reason { margin: 8px 0 0; font-size: 12px; color: #8899a6; line-height: 1.6; }
</style>
