import { useUserStore } from '../stores/user'

/**
 * 规则可见性的唯一判据：计分规则、权重、档位阈值、判据原文这些「怎么算出来的」只对超管显示，
 * 分数/温度/读数/清单这些「算出来是什么」对所有人显示。
 *
 * 模板里写 $canRule（main.js 注册成全局 getter），脚本里写 canSeeRules()。
 * 不复用 userStore.isSuperAdmin：仓库里已有一批 v-if="userStore.isSuperAdmin" 是**写入口**闸门，
 * 两种语义混用同一个字符串就 grep 不出「规则可见性」的覆盖面——而漏一处是这个仓库踩过的坑
 * （2651291 修的就是那颗待在 v-if/v-else 配对之外的按钮）。
 *
 * ⚠️ 这是界面层的遮蔽，不是安全边界：utils/scores.js 与 utils/temperatureBands.js 里的
 * 权重和分位切点照样打包进 bundle 发给每个浏览器。
 */
export function canSeeRules() {
  return useUserStore().isSuperAdmin
}
