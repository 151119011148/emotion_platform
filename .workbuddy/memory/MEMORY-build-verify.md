# emotion_platform 本机构建 / 前端验证

`MEMORY.md` 的伴生文件：那边只留最常用的判据，**动手编译或做前端验证之前，先读本文件**。

## 本机构建验证
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`，JDK `jdk1.8.0_251`。`cd emotion-server && mvn -DskipTests compile && mvn test`（418 用例全离线不连库；**`-q` 会吞 surefire 汇总**，数用例要解析 `target/surefire-reports/TEST-*.xml`）。
- 本机 Bash 的 PATH 是坏的（无 `ls/head/find/grep`）；PowerShell 输出不回显 → 跑命令用绝对路径 node/python。
- **`taskkill` 在 Git Bash 里必须写 `//F //T //PID`**（单斜杠被当路径报 `无效参数 - 'F:/'`）。停自己起的临时进程前**先确认 PID 归属**（曾误杀用户 5173 dev server）。
- 前端校验优先用 **vite 真编译链**：临时 config 另起端口指向验证实例，`GET /src/views/*.vue` 看是否 200 且产物含新代码——比 `@vue/compiler-sfc` 更硬。轻量替代 `%TEMP%/check_sfc.js`（判据＝`compileScript().bindings` 与模板产物 `_ctx.X` 取差集，**差集为空**；非 inline 模式产物统一编 `_ctx.X`，扫 `$setup.` 会假通过）。**别用** `node -e "import('vite').build()"`（挂十几分钟）。注意 `<style>` 是**独立模块**，查样式要取 `?vue&type=style&index=0&scoped=true&lang.css`，主模块里没有 CSS 文本。**`agent-browser` 本机未装**（需 ~500MB Chromium），别默认可用；`check_sfc.js` 对无 `<script>` 的纯模板/样式组件（如 `App.vue`）会跳过绑定比对，否则 `compileScript` 直接抛异常。
- **真渲染验证（无 agent-browser 时的替代）**：用本机 Edge。在 `emotion-web/` 根放一次性 html（vite dev 也服务工程根 `.html`）：**先** `localStorage.setItem('token',…)` **再** `location.replace('/waverider')`（反了会被路由守卫弹 `/login`；用 iframe 也得先写 localStorage 再 append）。再 `msedge --headless=new --disable-gpu --hide-scrollbars --window-size=1600,1000 --virtual-time-budget=25000 --user-data-dir=<临时目录> --screenshot=out.png <url>`（`ANOMALY: meaningless REX prefix` 是无害噪音）。要**量计算样式**＝iframe 里跑 `getComputedStyle` 把结果打到页面上再截图。**用完立刻删那个 html（里面是有效 token）**。
- **别肉眼判缩略截图的颜色**：1080 宽下采样图里 `#fafafa` 白行与深底分不出，我曾误读成「没问题」。硬判据＝Pillow 采样像素（已装 managed venv）或 `getComputedStyle`。
- **Edit 同一条消息里对同一文件连发两次会互相覆盖**；多改同文件要串行或 Write 脚本一次改完。脚本改多处时**每个 `old` 断言 `count == 1`**，用 `new` 自身做幂等护栏。
- **行尾：必须按原始字节判，不能用默认文本读。**`open(p, encoding='utf-8').read()` 是 universal newlines，会把 `\r\n` 翻成 `\n`，**任何文件都数出 CRLF=0**（曾据此把两个真 CRLF 的 `.vue` 误判成 LF，脚本全没命中）。判法：`open(p,'rb').read()` 数 `b.count(b'\r\n')` 与 `b.count(b'\n')`，相等且 >0 即 CRLF；写回 `open(p,'wb')` + `newline=''`。
- **仓库行尾是混的，别按扩展名猜**（`.vue` 21/4、`.java` 208/46、`.sql` 3/31、`.js` 9/2）。脚本姿势：先探 eol → `old.replace('\n', eol)` → 改完统一归一化。**Edit 工具自身会跨行尾归一化**，单点小改可照常 Edit。**新建文件（Write）落下来是 LF**；既有 Java 是 CRLF + 中文，Read/Edit 会当二进制拒掉，**改既有 Java 必须落脚本**。
- **多行/含反斜杠的脚本一律 Write 落文件，别用内联 `-e`/`-c`**：Git Bash 转义链会吞字符（`node -e` 的 `\n` 变字面 `/n`，曾把 `daily-bar:/n` 写进 application.yml 直接 `ScannerException` 拒启；`python -c` 的 `\\` 被吃成 `SyntaxError`）。校验：yml 用 `yaml.safe_load`；.vue 用 `check_sfc.js`；Java 用 `mvn test`。
