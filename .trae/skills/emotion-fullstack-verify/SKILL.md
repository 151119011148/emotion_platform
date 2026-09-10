---
name: "emotion-fullstack-verify"
description: "emotion-server(Java8/SpringBoot/MySQL)+emotion-web(Vue3/Vite) 全栈改动的标准验证流程：改代码→mvn test→幂等重放 schema→重启 8080/5173→curl 冒烟→浏览器点验。当修改情绪周期项目的打分引擎、schema、接口或前端页面并需要端到端验证时调用。"
---

# 情绪周期全栈验证流程（emotion-server + emotion-web）

适用于 `/Users/gaoying/Desktop/study/股市/code` 下的 emotion 双应用：
- 后端 `emotion-server`：Java 8 + Spring Boot 2.7 + MyBatis-Plus + MySQL，端口 **8080**，DB=`emotion_dashboard`（root/123456，profile `local` 的 application-local.yml 在仓库根）
- 前端 `emotion-web`：Vue3 + Element Plus + Vite，固定端口 **5173**（strictPort），proxy `/api → http://localhost:8080`
- 注意区分 wuwei 项目（5174/8081），不要把两个项目的 dev server 搞混

## 1. 改后端代码后：先编译再全量测试

```bash
cd /Users/gaoying/Desktop/study/股市/code/emotion-server
mvn -q compile                    # 快速编译检查
mvn test 2>&1 | grep -E 'Tests run: [0-9]+, Fail|BUILD' | tail
```

330 个测试必须全绿。关键测试：
- `BoardScoreCalculatorTest`：五维引擎算分（改期望值前自己按「权重归一化」手算：五子权和=0.90 时要除以 0.90；中位吹哨 ×0.8）
- `ScoringModelSeedParityTest`：**引擎常量 `BoardScoreCalculator.builtinTree()` 必须与 schema.sql 的 `@fid2` 种子逐字一致**。改任一边必须同步另一边，否则测试红
- `DailyRecordServiceFiveDimTest`：分数落列、未评≠0

## 2. Schema 变更：幂等设计 + 存量库重放（最容易漏的一步）

schema.sql 全部用 `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`，整体重放安全（无 DROP/DELETE）：

- **加列**：在文件末尾迁移段用 information_schema 先查后拼 ALTER（只补真缺的列，重放无害），照搬 v2 迁移段写法
- **改已有行的字段（如维名 label）**：INSERT IGNORE **不会**更新存量行，必须在迁移段加幂等 `UPDATE ... WHERE label = '旧名'`
- **新模型种子**：新库 INSERT IGNORE 即可；存量库靠重放补入，旧模型用 `UPDATE ... SET active=0` 下线

改完重放到存量库并验证：

```bash
cd emotion-server
mysql -uroot -p123456 emotion_dashboard < src/main/resources/schema.sql
mysql -uroot -p123456 emotion_dashboard -e "SELECT dim_key,label FROM t_scoring_dim WHERE model_id=(SELECT id FROM t_scoring_model WHERE model_key='five_dim_v2');"
```

漏迁移的典型症状：MyBatis 报 `Unknown column 'xxx' in 'field list'`，前端表现为"暂无数据/全维未评"，极像前端没接上——先查列是否存在。

## 3. 重启服务

```bash
kill $(lsof -ti :8080 -sTCP:LISTEN | head -1) 2>/dev/null; sleep 2
cd emotion-server && mvn spring-boot:run -q   # 后台运行，等到 Tomcat started 8080
cd emotion-web && npm run dev                 # strictPort，占不住就报错而不是跳号
```

**端口陷阱（本项目踩过多次）**：多个残留 dev server 会让 vite 静默跳到 5174/5175/5176，浏览器带着旧实例的过期 token 会误判为"登录坏了"。排查：

```bash
for p in 5173 5174 5175 5176; do pid=$(lsof -ti :$p -sTCP:LISTEN|head -1); \
  [ -n "$pid" ] && echo ":$p $(lsof -p $pid|awk '$4=="cwd"{print $NF}')"; done
```

用 cwd 辨认端口归属，杀掉重复实例，只留 emotion-web@5173、wuwei@5174。

## 4. curl 冒烟（在浏览器之前先用证据说话）

```bash
# 登录拿 JWT（真实账号 gaofeng/123456；需要测试账号可 POST /api/auth/register）
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"gaofeng","password":"123456"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
# 未带 token 返回 403 说明端点存在且被安全链保护；带 token 看数据
curl -s http://localhost:8080/api/market/indexes -H "Authorization: Bearer $TOKEN" | head -c 800
```

curl 通了再开浏览器，能把"后端问题/代理问题/前端问题"三层分开。

## 5. 前端静态验证

```bash
cd emotion-web && npm run build   # 抓语法/template 错误；chunk 体积警告可忽略
```

## 6. 浏览器点验（integrated_code_mode 的 Exec 里调 tools.*）

固定套路：

```js
// 登录（旧 token 会干扰，先清）
await tools.browser_evaluate({ script: "localStorage.clear(); 'ok'" })
await tools.browser_navigate({ url: 'http://localhost:5173/login' })
await tools.browser_wait_for({ time: 1 })
const s = await tools.browser_snapshot()           // 拿 textbox/button 的 eN 引用
await tools.browser_fill_form({ fields: [
  { element: '用户名', ref: 'e2', value: 'gaofeng' },
  { element: '密码', ref: 'e4', value: '123456' } ] })
const s1 = await tools.browser_snapshot()
const btn = s1.content[0].text.match(/button "登录" \[(e\d+)\]/)[1]
await tools.browser_click({ ref: btn })
await tools.browser_wait_for({ time: 2 })
```

要点：
- `browser_navigate/click` 后 **ref 全部失效**，必须重新 snapshot；`fill/type` 不使 ref 失效，多字段一次 `fill_form`
- 纯 `<div>` 不在快照可交互列表里，要做点击目标必须加 `role="button"`（配 `aria-label`）
- 取文本优先 `browser_evaluate` + `document.querySelector(...)`，正则解析 snapshot 的 yaml 易错
- 每页必查 `browser_console_messages`：过滤掉 `ERR_CONNECTION_RESET/ABORTED`（重启服务瞬间的噪音），关注 `No match found`（路由没注册/访问错端口实例）、`Invalid prop`（如 el-tag type 传了空串，必须用合法值 primary/success/info/warning/danger）
- 当日无行情明细是空态不是 bug；日期默认回落最近交易日，从别处带 `?date=` 跳来的页面都要读 `route.query.date`

## 7. 架构约束（改代码时不要违背）

- 打分：0-100 直加权，未评（缺读数）剔出分母**绝不兜 0**；MANUAL 键缺=未评
- 新只读页面接口放对应 controller（PRD 三页 `PrdController`，行情类 `MarketController`），返回 `ApiResponse<T>`，要登录态的用 `Authentication auth → (Long) auth.getPrincipal()`
- 自动取数走 Service 复用（如 `PrdMetricsService`），页面与打分引擎必须同源同一份结论
- 前端：api 统一加在 `src/api/modules.js`；路由/菜单/页面标题三者名称保持一致；日期选择页统一"今天→回落最近复盘日 + 支持 ?date="口径
