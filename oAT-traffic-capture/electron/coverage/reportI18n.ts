/**
 * JaCoCo 报告汉化注入（方案 A）：
 * - 向报告目录写一份 jacoco-resources/oat-i18n.js（列头双语 + 悬停中文释义，纯前端脚本）
 * - 给报告目录下所有 .html 追加 <script src="<相对层级>/jacoco-resources/oat-i18n.js">
 * - 报告数据与布局零改动；重复生成时幂等（已注入/已存在则跳过）
 */
import fs from 'fs'
import path from 'path'

const I18N_SCRIPT_NAME = 'oat-i18n.js'
const I18N_MARK = 'oat-i18n'

const I18N_JS = `/* oAT 注入：JaCoCo 列头双语 + 悬停中文释义（报告数据未改动） */
(function () {
  if (window.__oatI18n) return
  window.__oatI18n = true
  var MAP = {
    'Element':      { label: '元素',      tip: '一行 = 一个类 / 包；类名可点进方法级明细' },
    'Missed Instructions': { label: '未覆盖指令', tip: '没被执行到的字节码指令数量' },
    'Cov.':         { label: '覆盖',      tip: '覆盖率百分比（前一列对应维度：指令或分支）' },
    'Missed Branches': { label: '未覆盖分支', tip: 'if / switch 等分支未走到的数量' },
    'Missed':       { label: '未覆盖',    tip: 'Missed 开头的列都是"没测到的数量"' },
    'Cxty':         { label: '复杂度',    tip: '圈复杂度：代码路径数量，越大越难测全' },
    'Lines':        { label: '行',        tip: '源代码行数' },
    'Methods':      { label: '方法',      tip: '方法数' },
    'Classes':      { label: '类',        tip: '类数' },
    'Total':        { label: '总计',      tip: '全报告汇总' }
  }
  function apply() {
    var ths = document.querySelectorAll('thead th, tfoot th, th')
    for (var i = 0; i < ths.length; i++) {
      var th = ths[i]
      var t = (th.textContent || '').replace(/\\u00a0/g, ' ').trim()
      var m = MAP[t]
      if (m && !th.__oat) {
        th.__oat = true
        th.textContent = m.label
        th.title = m.tip + '（原列头：' + t + '）'
      }
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply)
  else apply()
})()
`

function injectScriptTag(html: string, prefix: string): string {
  if (html.includes(I18N_MARK)) return html
  const tag = `<script type="text/javascript" src="${prefix}jacoco-resources/${I18N_SCRIPT_NAME}"></script>`
  if (html.includes('</body>')) return html.replace('</body>', tag + '</body>')
  return html + tag
}

/** 报告生成后调用：写入 i18n 脚本并给全部 html 注入引用；任何失败只告警不阻断 */
export function injectReportI18n(reportDir: string): void {
  try {
    const resDir = path.join(reportDir, 'jacoco-resources')
    if (!fs.existsSync(path.join(reportDir, 'index.html')) || !fs.existsSync(resDir)) return
    fs.writeFileSync(path.join(resDir, I18N_SCRIPT_NAME), I18N_JS, 'utf8')

    let injected = 0
    const walk = (dir: string, depth: number) => {
      for (const name of fs.readdirSync(dir)) {
        const p = path.join(dir, name)
        const st = fs.statSync(p)
        if (st.isDirectory()) { walk(p, depth + 1); continue }
        if (!name.endsWith('.html')) continue
        const prefix = '../'.repeat(depth)
        const html = fs.readFileSync(p, 'utf8')
        const out = injectScriptTag(html, prefix)
        if (out !== html) { fs.writeFileSync(p, out, 'utf8'); injected++ }
      }
    }
    walk(reportDir, 0)
    console.info('[覆盖率] 报告汉化注入完成: %s（%d 个页面）', reportDir, injected)
  } catch (e) {
    console.warn('[覆盖率] 报告汉化注入失败（不影响报告本身）: %s', (e as Error)?.message || e)
  }
}
