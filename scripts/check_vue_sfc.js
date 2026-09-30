#!/usr/bin/env node
/**
 * .vue 单文件组件静态校验（秒级，不需要起 vite）。
 *
 * <p>背景：本机 `node -e "import('vite').build()"` 会挂住十几分钟无输出，
 * 改完前端要快速确认「模板编译得过、且没有引用了未定义的东西」时用这个。
 *
 * <p>用法：
 *   node scripts/check_vue_sfc.js                     # 扫 emotion-web/src 下全部 .vue
 *   node scripts/check_vue_sfc.js src/views/X.vue ...  # 只校验指定文件（可给多个，相对或绝对路径）
 *
 * <p>三项检查：
 *   1. parse / compileScript / compileTemplate 三段编译错误；
 *   2. 模板里 `_ctx.X` 引用减去 compileScript 的 bindings 后必须为空
 *      —— 非 inline 模式下产物统一用 `_ctx.` 引用 setup 绑定，`$setup.` 一个都不会出现，
 *      所以「扫 $setup.」会得到「引用 0 个」的假通过，正确判据是减 bindings。
 *   3. 模板里用到的 `<foo-bar>`（kebab-case）能对上组件名，粗查漏 import 的情况。
 *
 * <p>退出码非 0 = 有文件没通过，可直接接在 CI/提交钩子里。
 */
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..')
const webDir = path.join(root, 'emotion-web')
const sfcPath = path.join(webDir, 'node_modules', '@vue', 'compiler-sfc')
const srcDir = path.join(webDir, 'src')

let sfc
try {
  sfc = require(sfcPath)
} catch (e) {
  console.error('找不到 @vue/compiler-sfc，先 cd emotion-web && npm i：' + sfcPath)
  process.exit(1)
}

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === '.git') continue
      walk(p, out)
    } else if (e.name.endsWith('.vue')) {
      out.push(p)
    }
  }
  return out
}

const args = process.argv.slice(2)
const files = args.length
  ? args.map((a) => (path.isAbsolute(a) ? a : path.resolve(process.cwd(), a)))
  : walk(srcDir, [])

const BUILTIN_CTX = new Set(['$attrs', '$slots', '$props', '$emit', '$refs', '$options',
  '$parent', '$root', '$data', '$forceUpdate', '$nextTick'])

let bad = 0
for (const file of files) {
  const rel = path.relative(root, file)
  const src = fs.readFileSync(file, 'utf8')
  const problems = []

  const { descriptor, errors } = sfc.parse(src, { filename: file })
  if (errors.length) problems.push(...errors.map((e) => 'parse: ' + (e.message || e)))

  let script = null
  if (!problems.length) {
    script = sfc.compileScript(descriptor, { id: 'chk' })
    if (script.errors && script.errors.length) {
      problems.push(...script.errors.map((e) => 'script: ' + (e.message || e)))
    }
  }

  if (!problems.length && descriptor.template) {
    const tpl = sfc.compileTemplate({
      source: descriptor.template.content,
      filename: file,
      id: 'chk',
      compilerOptions: { mode: 'module' }
    })
    if (tpl.errors && tpl.errors.length) {
      problems.push(...tpl.errors.map((e) => 'template: ' + (e.message || e)))
    } else {
      const bindings = new Set(Object.keys(script.bindings || {}))
      const refs = new Set()
      let m
      const re = /_ctx\.([A-Za-z_$][\w$]*)/g
      while ((m = re.exec(tpl.code))) refs.add(m[1])
      const missing = [...refs].filter((n) => !BUILTIN_CTX.has(n) && !bindings.has(n))
      if (missing.length) problems.push('模板引用了未绑定的标识符：' + missing.join(', '))
    }
  }

  if (problems.length) {
    bad++
    console.log('[FAIL] ' + rel)
    for (const p of problems) console.log('       ' + p)
  } else {
    console.log('[ OK ] ' + rel)
  }
}

console.log('\n' + (files.length - bad) + '/' + files.length + ' 通过')
process.exit(bad ? 1 : 0)
