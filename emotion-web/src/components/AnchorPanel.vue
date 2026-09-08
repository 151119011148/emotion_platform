<template>
  <div class="anchor-panel">
    <section class="block">
      <div class="block-head">
        <div class="block-title">周期阵眼 · 跨度</div>
        <div class="block-tools">
          <span class="badge" :class="scoreClass(anchor?.score)">
            第8维 {{ anchor?.score == null ? '未评' : anchor.score }}
          </span>
          <el-button size="small" @click="openCreate">设阵眼</el-button>
        </div>
      </div>

      <p class="note">{{ anchorNote }}</p>

      <div v-if="!anchorItems.length" class="empty">{{ emptyAnchor }}</div>
      <ul v-else class="rows">
        <li v-for="it in anchorItems" :key="it.id" class="row">
          <div class="row-head">
            <span class="stock">{{ it.name }} <em>{{ it.code }}</em></span>
            <span class="role">{{ it.roleLabel }}</span>
            <span class="badge" :class="scoreClass(it.score)">{{ it.score == null ? '未评' : it.score + ' 分' }}</span>
            <span class="row-tools">
              <el-button link size="small" @click="openEdit(it)">改</el-button>
              <el-button link size="small" @click="remove(it)">删</el-button>
            </span>
          </div>
          <div class="row-meta">
            <span>{{ spanText(it) }}</span>
            <span>最高 {{ it.maxBoard ?? '—' }} 板</span>
            <span :class="pctClass(it.pct)">当日 {{ signed(it.pct) }}%</span>
            <span>最低 {{ signed(it.lowPct) }}%</span>
            <span>距跨度高点 {{ signed(it.drawdownPct) }}%</span>
          </div>
          <div class="row-tags">
            <span v-if="it.closeLimitDown" class="tag bad">收盘跌停</span>
            <span v-if="it.touchedLimitDown && !it.closeLimitDown" class="tag bad">盘中触板</span>
            <span v-if="it.brokeToday" class="tag bad">断板</span>
            <span v-if="it.newSpanHigh" class="tag good">创跨度新高</span>
            <span v-if="it.cycleTag" class="tag muted">{{ it.cycleTag }}</span>
          </div>
        </li>
      </ul>
    </section>

    <el-dialog v-model="dialog" :title="form.id ? '修改阵眼' : '设阵眼'" width="520px" append-to-body destroy-on-close>
      <el-form label-width="86px" label-position="left">
        <el-form-item label="股票">
          <el-select
            v-model="form.code"
            filterable
            remote
            reserve-keyword
            :remote-method="searchStock"
            :loading="searching"
            placeholder="输入代码或名称搜（名字以后端代码表为准）"
            style="width: 100%"
          >
            <el-option
              v-for="s in options"
              :key="s.code"
              :label="optionLabel(s)"
              :value="s.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="周期总龙（本轮领涨的那一只）" value="LEADER" />
            <el-option label="周期阵眼（判断退潮的哨兵）" value="CYCLE" />
          </el-select>
        </el-form-item>
        <el-form-item label="跨度起点">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="起爆日或你认定的锚点日" style="width: 100%" />
        </el-form-item>
        <el-form-item label="跨度终点">
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD"
                          clearable placeholder="留空 = 仍在位" style="width: 100%" />
        </el-form-item>
        <el-form-item label="归组">
          <el-input v-model="form.cycleTag" maxlength="20" placeholder="如 2026-07，只用于分组显示" />
        </el-form-item>
        <el-form-item label="依据">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit
                    placeholder="为什么是它——这段只给你自己看" />
        </el-form-item>
      </el-form>
      <p class="dialog-hint">跨度、最高连板、断板日、当日涨跌都是后端从日 K 现算的，这里填错只会算到别的票身上。</p>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { anchorApi, stockApi } from '../api/modules'
import { signed } from '../utils/scores'

const props = defineProps({
  /** 页头那条记录：弹窗里「跨度起点」的默认值是它那天，面板自己不读打分列 */
  record: { type: Object, default: null },
  /** GET /api/anchors 同日结果 */
  anchor: { type: Object, default: null }
})
const emit = defineEmits(['changed'])

const anchorItems = computed(() => props.anchor?.items || [])

const anchorNote = computed(() => props.anchor?.note || '阵眼未取到：第 8 维不计入分母')

/** 空态只有一句话的位置，所以两种"没有"必须说得不一样。 */
const emptyAnchor = computed(() => {
  if (anchorItems.value.length) return ''
  return props.anchor?.available
    ? '当日无行情：周末、停牌或上游没数据（第 8 维不计入分母，不是 0 分）'
    : '未设阵眼：第 8 维不计入分母（不是 0 分）'
})

/**
 * 03 篇四档与带符号数字都在 utils/scores 里，卡片和面板说的是同一套。
 * -1 比 0 还重，可数组下标取不到负数，不单列一支就会掉进 || 's1'，
 * 用最中性的灰画掉最坏的那个读数。
 */
function scoreClass(score) {
  if (score == null) return 'none'
  if (score < 0) return 'sn'
  return ['s0', 's1', 's2', 's3'][score] || 's1'
}

function pctClass(p) {
  const v = Number(p)
  if (p == null || Number.isNaN(v)) return ''
  return v > 0 ? 'up' : v < 0 ? 'down' : ''
}

function spanText(it) {
  if (!it.startDate) return '—'
  const end = it.endDate ? short(it.endDate) : '今'
  return `${short(it.startDate)} → ${end} · ${it.tradeDays ?? '—'} 个交易日`
}

/** 起点 2026-07-10 上只想看 07-10；年份留给跨度天数那一截。 */
function short(iso) {
  return iso ? iso.slice(5) : ''
}

const dialog = ref(false)
const saving = ref(false)
const searching = ref(false)
const options = ref([])
const form = reactive({
  id: null, code: '', role: 'CYCLE', startDate: '', endDate: '', cycleTag: '', note: ''
})

/** 编辑时先塞进列表的那一条没有板块（VO 里就没有），不能读出「600664 · 」这种带尾缀的串。 */
function optionLabel(s) {
  return s.board ? `${s.name} ${s.code} · ${s.board}` : `${s.name} ${s.code}`
}

async function searchStock(q) {
  const key = (q || '').trim()
  if (key.length < 1) {
    options.value = []
    return
  }
  searching.value = true
  try {
    const res = await stockApi.search(key)
    options.value = res.data || []
  } catch (e) {
    options.value = []
  } finally {
    searching.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    id: null, code: '', role: 'CYCLE', startDate: props.record?.tradeDate || '', endDate: '', cycleTag: '', note: ''
  })
  options.value = []
  dialog.value = true
}

function openEdit(it) {
  Object.assign(form, {
    id: it.id, code: it.code, role: it.role || 'CYCLE', startDate: it.startDate,
    endDate: it.endDate || '', cycleTag: it.cycleTag || '', note: it.note || ''
  })
  // 编辑时不下拉也能保存：把当前这只直接放进选项里
  options.value = [{ code: it.code, name: it.name, board: '' }]
  dialog.value = true
}

async function submit() {
  if (!form.code || !form.startDate) {
    ElMessage.warning('代码和跨度起点都要填')
    return
  }
  const body = {
    stockCode: form.code,
    role: form.role,
    cycleTag: form.cycleTag || null,
    startDate: form.startDate,
    endDate: form.endDate || null,
    note: form.note || null
  }
  saving.value = true
  try {
    if (form.id) await anchorApi.update(form.id, body)
    else await anchorApi.create(body)
    ElMessage.success('已保存，跨度指标读的时候现算')
    dialog.value = false
    emit('changed')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(it) {
  try {
    await ElMessageBox.confirm(`删除 ${it.name} 的阵眼登记？第 8 维会退回未评。`, '确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await anchorApi.remove(it.id)
    ElMessage.success('已删除')
    emit('changed')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败')
  }
}
</script>

<style scoped>
.anchor-panel {
  display: grid;
  /* 中文的 min-content 是一个字宽，必须 minmax(0,..) 才不会被压成竖排；
     min(340px,100%) 是另一半：容器比 340 还窄时（窄栏、内嵌预览）宁可单列挤压，不要把整块顶出去横向滚动 */
  grid-template-columns: repeat(auto-fit, minmax(min(340px, 100%), 1fr));
  gap: 20px;
  margin-bottom: 20px;
}
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  min-width: 0;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.block-title {
  font-size: 15px;
  font-weight: 600;
  color: #e1e8ed;
}
.block-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
.note {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8899a6;
  line-height: 1.5;
}
.empty {
  margin-top: 14px;
  padding: 14px;
  border: 1px dashed #2d3748;
  border-radius: 8px;
  color: #8899a6;
  font-size: 13px;
}
.rows {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row {
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 10px 12px;
  min-width: 0;
}
.row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stock {
  color: #e1e8ed;
  font-weight: 600;
  font-size: 14px;
}
.stock em {
  font-style: normal;
  color: #8899a6;
  font-weight: 400;
  font-size: 12px;
  margin-left: 2px;
}
.role {
  font-size: 11px;
  color: #8899a6;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 1px 5px;
}
.row-tools {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.row-meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12px;
  color: #8899a6;
}
.row-tags {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  font-size: 11px;
  border-radius: 4px;
  padding: 1px 6px;
}
.tag.bad {
  color: #93c5fd;
  background: rgba(59, 130, 246, 0.14);
}
.tag.good {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.14);
}
.tag.muted {
  color: #8899a6;
  border: 1px solid #2d3748;
}
.badge {
  font-size: 11px;
  border-radius: 10px;
  padding: 2px 8px;
  border: 1px solid #2d3748;
  color: #8899a6;
  white-space: nowrap;
  flex-shrink: 0;
}
.badge.s3 { color: #fca5a5; border-color: #7f1d1d; background: rgba(239, 68, 68, 0.12); }
.badge.s2 { color: #fbbf24; border-color: #78350f; background: rgba(245, 158, 11, 0.12); }
.badge.s1 { color: #cbd5e1; border-color: #334155; }
.badge.s0 { color: #93c5fd; border-color: #1e3a5f; background: rgba(59, 130, 246, 0.12); }
/* 负分比 s0 再深一档：0 分是"差"，-1 是"崩了"，同色就白分开这两档了 */
.badge.sn { color: #bfdbfe; border-color: #1e40af; background: rgba(30, 64, 175, 0.32); }
.badge.none { border-style: dashed; }
.up { color: #ef4444; }
.down { color: #60a5fa; }
.dialog-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8899a6;
  line-height: 1.5;
}
</style>
