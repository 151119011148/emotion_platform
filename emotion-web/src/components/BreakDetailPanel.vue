<template>
  <div class="bd" v-loading="loading">
    <div class="row">
      <span class="title">破壁详情</span>
      <span class="ntag" :class="d && d.event === 'BREAK' ? 'nt-SPACE_BREAK_NEXT' : 'nt-SPACE_BREAK'">
        {{ eventText }}
      </span>
      <span class="day">{{ date }}</span>
      <span class="subject" v-if="d && d.subject">
        {{ d.subject.name }}（{{ d.subject.code }}）{{ d.subject.board != null ? d.subject.board + '板' : '' }}
      </span>
      <span class="spacer"></span>
      <el-button size="small" @click="load" :loading="loading">重新取数</el-button>
      <el-button v-if="userStore.isSuperAdmin" size="small" type="primary" :disabled="!canCreate || !date"
        :loading="creating" @click="create">
        {{ d && d.event === 'BREAK' ? '立为节点（两行）' : '立为节点（试探一行）' }}
      </el-button>
    </div>

    <!-- 线：追的是哪条、这条谁钉的。挂账旧龙与混沌期一起摆在这一行，读的人不用去曲线上找 -->
    <p class="line" v-if="d">
      <template v-if="d.event === 'BREAK'">
        破掉的是 {{ dash(d.prevHigh) }} 板线；那次试探发生在 {{ d.probeDate || '—' }}
      </template>
      <template v-else>追 {{ dash(ceilingShown) }} 板破壁线</template>
      <template v-if="d.lineOriginDate">
        · 这条线由 {{ d.lineOriginDate }} 的
        {{ d.lineOriginStock ? d.lineOriginStock.name : '—' }} 立起
      </template>
      <template v-if="d.oldDragonHeight != null">｜挂账旧龙 {{ d.oldDragonHeight }} 板</template>
      <el-tag v-if="d.chaos" size="small" type="warning" effect="plain">钉线倒计时</el-tag>
    </p>

    <el-alert v-if="d && d.detailAvailable === false" class="gap-alert" type="info" :closable="false"
      :title="d.detailMissingReason || '这天没有逐只明细'" />

    <div class="grid" v-if="d">
      <div class="cell">
        <span class="cell-label">同属性助攻</span>
        <span class="cell-value">
          首 {{ dash(a.sameIndustryFirst) }} ／ 二 {{ dash(a.sameIndustrySecond) }}
          ／ 3+ {{ dash(a.sameIndustryThirdPlus) }}｜合计 {{ dash(a.total) }} 只
        </span>
        <span class="cell-sub">
          梯队{{ a.ladderOk === true ? '立住' : a.ladderOk === false ? '没立住' : '判不了' }}（线 2 只）
          · 当天首板 {{ dash(a.firstBoardTotal) }} 只散在 {{ dash(a.firstBoardIndustries) }} 个行业
        </span>
        <span class="cell-foot">只展示：不进闸门、不改权重</span>
      </div>

      <div class="cell">
        <span class="cell-label">主角盘口</span>
        <span class="cell-value" v-if="b">
          {{ PATTERN_LABEL[b.pattern] || dash(b.pattern) }}
          <template v-if="b.sealForm">·{{ b.sealForm }}</template>
          · 炸板 {{ dash(b.breakCount) }} 次 · 换手 {{ dash(b.turnoverRate) }}%
          · 封单/流通 {{ dash(b.sealRatio) }}%
        </span>
        <span class="cell-value" v-else>—</span>
        <span class="cell-sub" v-if="b">
          首封 {{ fmtSeal(b.firstSealTime) }}<template v-if="b.sealAmount != null">
            ｜封单 {{ moneyText(b.sealAmount) }}</template><template v-if="b.oneWordKilling">
            ｜<b class="bad">一字缩量断魂刀</b></template>
        </span>
        <span class="cell-foot">烂板与缩量他没有数值口径，这里只列数</span>
      </div>

      <div class="cell">
        <span class="cell-label">情绪闸门</span>
        <span class="cell-value" v-if="g">
          炸板率 {{ dash(g.brokenBoardRate) }}% · 昨涨停溢价 {{ dash(g.yesterdayLimitPremium) }}%
          · 涨停 {{ dash(g.limitUpCount) }} 家
        </span>
        <span class="cell-value" v-else>—</span>
        <span class="cell-sub">阶段：{{ g && g.stage ? g.stage : '未复盘' }}（判据 &gt;50% / &lt;0 / 退潮）</span>
        <ul class="warn" v-if="d.gateWarnings && d.gateWarnings.length">
          <li v-for="(w, i) in d.gateWarnings" :key="'g' + i">{{ w }}</li>
        </ul>
      </div>

      <div class="cell">
        <span class="cell-label">次日结算</span>
        <span class="cell-value" v-if="o">
          <b :class="outcomeClass(o.result)">{{ outcomeText(o.result) }}</b>
          <template v-if="o.nextDate">｜{{ o.nextDate }} {{ o.nextBoard != null ? o.nextBoard + '板' : '' }}</template>
        </span>
        <span class="cell-value" v-else>这天已经是结论本身</span>
        <span class="cell-sub" v-if="o && o.reason">{{ o.reason }}</span>
      </div>
    </div>

    <div class="stocks" v-if="a && ((a.firstStocks && a.firstStocks.length) || (a.followStocks && a.followStocks.length))">
      <span class="cell-label">助攻名单</span>
      <el-tag v-for="s in a.firstStocks" :key="'f' + s.code" size="small" type="success" effect="plain">
        {{ s.name }}·首板
      </el-tag>
      <el-tag v-for="s in a.followStocks" :key="'k' + s.code" size="small" effect="plain">
        {{ s.name }}·{{ s.board }}板
      </el-tag>
    </div>

    <p class="impact" v-if="d && d.scoreImpact">
      <span class="impact-lead">立成节点会改写的分</span>{{ d.scoreImpact }}
    </p>
    <p class="receipt" v-if="receipt">{{ receipt }}</p>
  </div>
</template>

<script setup>
/**
 * 曲线上那颗 ☆/★ 的辅助验证面板：线、主角、助攻、盘口、情绪闸门、次日结算，加「立为节点」。
 *
 * 判定不在这里——哪天是试探、哪天算破壁成功，唯一出处是后端 TiantiService.detectBreaks，
 * 曲线上的标记与这条面板读的是同一份。这里连一个阈值都没有写第二遍。
 *
 * 所有数值都可能是 null，<b>null 是"不知道"不是 0</b>：2026-08-03 之前的名义天梯只有板高没有逐只明细，
 * 兜成 0 会把"没有助攻"伪造进一段根本没法验证的历史里。所以空白一律显示「—」并顶出原因条。
 */
import { ref, computed, watch } from 'vue'
import { prdApi, nodeApi } from '../api/modules'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../stores/user'

const props = defineProps({
  date: { type: String, required: true }
})
const emit = defineEmits(['created'])
const userStore = useUserStore()

const PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }

const d = ref(null)
const loading = ref(false)
const creating = ref(false)
const receipt = ref('')

async function load() {
  if (!props.date) return
  loading.value = true
  try {
    const res = await prdApi.breakDetail(props.date)
    d.value = res.data || null
  } catch (e) {
    d.value = null
  } finally {
    loading.value = false
  }
}

/**
 * 立节点会写库，而且是往<b>全平台共享的节点表</b>里写：成功日一次落两行。
 * 所以点下去先复述一遍要落什么，再由他确认——回执里那句改分说明在这一步就该先看到。
 */
async function create() {
  if (!canCreate.value) return
  const rows = d.value && d.value.event === 'BREAK' ? '两行（破壁成功 + 前一天那次试探）' : '一行（试探破壁）'
  try {
    await ElMessageBox.confirm(
      `把 ${props.date} 立成节点：落${rows}。${(d.value && d.value.scoreImpact) || ''}`,
      '立为破壁节点', { confirmButtonText: '立', cancelButtonText: '再看看', type: 'warning' }
    )
  } catch (e) {
    return
  }
  creating.value = true
  try {
    const res = await nodeApi.createBreak(props.date)
    const out = res.data || {}
    receipt.value = (out.alreadyExists
      ? '这几行早就立过了，这次一行都没新插：'
      : `已立 ${(out.rows || []).length} 行：`)
      + (out.rows || []).map((r) => `${r.d0Date} ${r.nodeTypeLabel}`).join('、')
      + (out.scoreImpact ? `｜${out.scoreImpact}` : '')
    ElMessage.success(out.alreadyExists ? '这天已经立过，没有重复落库' : '已立为节点，可在节点追踪页复算')
    emit('created')
  } catch (e) {
    receipt.value = ''
  } finally {
    creating.value = false
  }
}

const eventText = computed(() => {
  const e = d.value && d.value.event
  if (e === 'PROBE') return '☆ 试探破壁'
  if (e === 'BREAK') return '★ 破壁成功'
  return '这天没有破壁事件'
})

const canCreate = computed(() => !!d.value && (d.value.event === 'PROBE' || d.value.event === 'BREAK'))

/**
 * 面板上「追 X 板线」这一格：试探日读的是<b>进来这天时挂着的那条线</b>，
 * 服务端已经在 break-detail 里按这个口径给（当天的 ceiling 会跟着追平那只抬上去）。
 */
const ceilingShown = computed(() => {
  const v = d.value || {}
  return v.event === 'BREAK' ? v.prevHigh : v.ceiling
})

const a = computed(() => (d.value && d.value.assist) || {})
const b = computed(() => (d.value && d.value.board) || null)
const g = computed(() => (d.value && d.value.gate) || null)
const o = computed(() => (d.value && d.value.outcome) || null)

function dash(v) {
  return v === null || v === undefined || v === '' ? '—' : v
}

function outcomeText(r) {
  return { SUCCESS: '续板成功', FAILED: '未续板', PENDING: '待次日' }[r] || '—'
}

function outcomeClass(r) {
  return r === 'SUCCESS' ? 'good' : r === 'FAILED' ? 'bad' : ''
}

/** 首封时间后端给的是 HHMMSS 整数；没读过就是没读过，不兜 0 成一个看起来正常的 00:00。 */
function fmtSeal(v) {
  if (v == null) return '—'
  const text = String(v).padStart(6, '0')
  return `${text.slice(0, 2)}:${text.slice(2, 4)}`
}

/** 封单额：与天梯、首板池那两张卡同一个读法，同一页里三种金额写法没法比。 */
function moneyText(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  if (n >= 1e8) return (n / 1e8).toFixed(2) + ' 亿'
  if (n >= 1e4) return (n / 1e4).toFixed(0) + ' 万'
  return n.toFixed(0) + ' 元'
}

watch(() => props.date, load, { immediate: true })
</script>

<style scoped>
.bd {
  margin-top: 12px;
  padding: 14px 16px;
  background: #0f1419;
  border: 1px solid #2b3a4a;
  border-radius: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.title {
  color: #e1e8ed;
  font-weight: 600;
  font-size: 14px;
}
.day,
.subject {
  font-size: 12px;
  color: #cbd5e1;
}
.subject {
  color: #e1e8ed;
  font-weight: 600;
}
.spacer {
  flex: 1;
}
.line {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #8899a6;
}
.gap-alert {
  margin-top: 10px;
}
.gap-alert :deep(.el-alert__title) {
  font-size: 12px;
  line-height: 1.6;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px 16px;
  margin-top: 12px;
}
.cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  background: #111927;
  border: 1px solid #1f2b38;
  border-radius: 8px;
}
.cell-label {
  font-size: 11px;
  color: #5f7488;
}
.cell-value {
  font-size: 13px;
  color: #e1e8ed;
  line-height: 1.5;
}
.cell-sub {
  font-size: 11px;
  color: #8899a6;
  line-height: 1.5;
}
.cell-foot {
  font-size: 11px;
  color: #5f7488;
}
.warn {
  margin: 4px 0 0;
  padding-left: 16px;
  font-size: 11px;
  line-height: 1.6;
  color: #f59e0b;
}
.stocks {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.impact,
.receipt {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #cbd5e1;
}
.impact-lead {
  color: #f59e0b;
  margin-right: 6px;
}
.receipt {
  color: #2d8a4e;
}
.good {
  color: #2d8a4e;
}
.bad {
  color: #dc2626;
}
</style>
