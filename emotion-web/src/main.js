import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { canSeeRules } from './utils/ruleVisibility'

const app = createApp(App)
const pinia = createPinia()

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
// 挂 getter 而不是挂值：求值发生在 render 期间，读到的 role ref 会被当前组件的渲染副作用收集，
// 所以管理员中途降级某个账号、下一次挂载覆写 role 之后，规则会跟着重新隐藏，不用手动通知。
// 放在 use(pinia) 之后——canSeeRules() 要取 store。
Object.defineProperty(app.config.globalProperties, '$canRule', { get: canSeeRules })
app.use(router)
app.use(ElementPlus)
app.mount('#app')
