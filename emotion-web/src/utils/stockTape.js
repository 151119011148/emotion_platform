import { ref } from 'vue'
import { stockTapeApi } from '../api/modules'

/**
 * 个股分时的全站唯一一份状态：任何页面点到个股都调 {@link openStockTape}，
 * 弹窗只挂在 App.vue 上渲染一次。
 *
 * <p>为什么不做成 pinia store 也不做成每页一个组件：这是「一只票的一天」，
 * 十四个页面要的是同一件事。各页自己挂弹窗就会有第十四份 chart 实例和第十四种空态长相，
 * 而缺数那句话（这一天永久没有 / 抓了但没这只 / 还没到 19:12）只该有一处措辞。
 *
 * <p>{@link openStockTape} 不等上一次响应：连点两只票时靠对号（{@code seq}）作废在途响应，
 * 先发出的那个后回来，不许把后点的那只盖掉。
 */
const visible = ref(false)
const code = ref('')
const name = ref('')
const date = ref('')
const loading = ref(false)
/** 后端 StockTapeVO 原样。 */
const tape = ref(null)
/** 请求本身失败（网络／越权）。缺数不走这里——那是 available=false，不是错误。 */
const error = ref('')

let seq = 0

export function useStockTape() {
  return { visible, code, name, date, tape, loading, error }
}

export function openStockTape(nextCode, nextName, nextDate) {
  if (!nextCode) return
  const mine = ++seq
  visible.value = true
  code.value = String(nextCode)
  name.value = nextName || ''
  date.value = nextDate || ''
  loading.value = true
  error.value = ''
  tape.value = null
  stockTapeApi
    .tape(code.value, date.value || undefined)
    .then((res) => {
      if (mine === seq) tape.value = res.data
    })
    .catch((e) => {
      if (mine === seq) error.value = e?.message || '分时取数失败'
    })
    .finally(() => {
      if (mine === seq) loading.value = false
    })
}

/** 关掉就作废对号：在途的响应回来时不再写状态，下一次打开不会先看见上一只票的图。 */
export function closeStockTape() {
  seq += 1
  visible.value = false
  loading.value = false
}
