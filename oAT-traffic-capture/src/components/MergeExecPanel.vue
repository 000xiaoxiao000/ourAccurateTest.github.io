<script setup lang="ts">
/**
 * 手动合并 exec 面板
 *
 * 触发时机：**手动点按钮** —— 不做「检测到新 exec 自动合并」。
 * 复用既有 coverageMerge IPC（底层就是 jacoco-cli merge --destfile），
 * 只是把藏在「工具与报告」命令卡片里的能力做成一个独立、显式的操作入口：
 * 多选 .exec → 指定输出目录/文件名 → 点「开始合并」。
 */
import { computed, ref } from 'vue'
import type { CoverageExecInfo } from '../types/traffic'

const props = defineProps<{
  /** 本会话已抓取的 exec（来自 dump 等命令的产出），作为快捷勾选来源 */
  execs: CoverageExecInfo[]
}>()
const emit = defineEmits<{ (e: 'merged', file: string): void }>()

/** 用户挑选的 exec 绝对路径（可来自文件选择器，也可勾选本会话产出） */
const selected = ref<string[]>([])
const destDir = ref('')
const destName = ref('jacoco-merged.exec')

const busy = ref(false)
const err = ref('')
const msg = ref('')

const destAbs = computed(() => {
  const name = destName.value.trim() || 'jacoco-merged.exec'
  if (destDir.value.trim()) return destDir.value.trim().replace(/\/$/, '') + '/' + name.replace(/^\//, '')
  return name
})

function base(path: string) {
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return i >= 0 ? path.slice(i + 1) : path
}

function toggleQuick(file: string) {
  const i = selected.value.indexOf(file)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(file)
}

function isQuickChecked(file: string) {
  return selected.value.includes(file)
}

async function pickExecs() {
  const r = await window.electronAPI?.coveragePickPath({
    pick: 'file', multi: true, extensions: ['exec'], title: '选择要合并的 .exec（可多选）'
  })
  const paths = (r?.paths ?? []).filter(Boolean)
  for (const p of paths) if (!selected.value.includes(p)) selected.value.push(p)
}

async function pickDir() {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择合并结果输出目录' })
  if (r?.success && r.path) destDir.value = r.path
}

function remove(path: string) {
  const i = selected.value.indexOf(path)
  if (i >= 0) selected.value.splice(i, 1)
}

async function merge() {
  err.value = ''
  msg.value = ''
  if (selected.value.length < 2) {
    err.value = '请至少选择 2 个 .exec 文件再合并'
    return
  }
  busy.value = true
  try {
    const r = await window.electronAPI?.coverageMerge({
      execs: [...selected.value],
      destfile: destAbs.value
    })
    if (!r?.success) {
      err.value = r?.error || '合并失败'
      return
    }
    msg.value = `✓ 已合并 ${selected.value.length} 个 exec → ${r.file || destAbs.value}`
    emit('merged', r.file || destAbs.value)
  } catch (e) {
    err.value = '合并异常：' + ((e as Error)?.message || String(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="merge-exec">
    <div class="card">
      <div class="card-head">
        <h3>合并 exec <span class="tag tag-blue">手动触发</span></h3>
        <span class="muted">把多个 .exec 合并为一个，供报告 / 影响分析共用</span>
      </div>
      <div class="sub">
        选择要合并的 exec（≥2 个），指定输出文件，然后<b>手动点击「开始合并」</b>。
        本工具不会在检测到新 exec 时自动合并，避免把未完成的采集混进结果。
      </div>

      <div class="sel-head">
        <span class="sel-label">待合并的 exec（{{ selected.length }}）</span>
        <button type="button" class="btn btn-ghost" @click="pickExecs">+ 选择 .exec 文件（可多选）</button>
      </div>

      <div class="sel-list" v-if="selected.length">
        <div class="sel-item" v-for="p in selected" :key="p">
          <code>{{ base(p) }}</code>
          <span class="muted tiny full">{{ p }}</span>
          <button type="button" class="btn btn-ghost" @click="remove(p)">移除</button>
        </div>
      </div>
      <p v-else class="muted">还没有选文件 —— 点上面的按钮选择，或勾选下方「本会话已抓取」的 exec</p>

      <div v-if="props.execs.length" class="quick">
        <div class="sel-label">本会话已抓取的 exec（点击勾选）</div>
        <label class="quick-item" v-for="e in props.execs" :key="e.file">
          <input type="checkbox" :checked="isQuickChecked(e.file)" @change="toggleQuick(e.file)" />
          <span class="mono">{{ base(e.file) }}</span>
          <span class="muted tiny" v-if="e.key">key={{ e.key }}</span>
        </label>
      </div>

      <div class="out-row">
        <label class="out-field">
          <span class="sel-label">输出目录（留空 = 应用数据目录）</span>
          <span class="out-ctl">
            <input v-model="destDir" type="text" placeholder="留空则用默认 exec 目录" />
            <button type="button" class="btn btn-ghost" @click="pickDir">选目录</button>
          </span>
        </label>
        <label class="out-field">
          <span class="sel-label">输出文件名</span>
          <input v-model="destName" type="text" />
        </label>
      </div>
      <div class="dest-preview muted tiny">目标文件：{{ destAbs }}</div>

      <div class="actions">
        <button type="button" class="btn btn-primary" :disabled="busy" @click="merge">
          {{ busy ? '合并中…' : '开始合并' }}
        </button>
      </div>
      <div v-if="err" class="err-box">{{ err }}</div>
      <div v-if="msg" class="ok-box">{{ msg }}</div>
    </div>
  </div>
</template>

<style scoped>
.merge-exec { display: flex; flex-direction: column; }
.sub { font-size: 12px; color: #64748b; line-height: 1.7; margin: 6px 0 14px; }
.sel-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.sel-label { font-size: 12px; font-weight: 500; color: #334155; }
.sel-list { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.sel-item { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; background: #f8fafc; border: 1px solid #eef1f5; border-radius: 8px; padding: 6px 10px; }
.sel-item .full { flex-basis: 100%; }
.tiny { font-size: 11px; }
.quick { border-top: 1px dashed #e2e8f0; padding-top: 10px; margin: 10px 0 14px; }
.quick-item { display: flex; align-items: center; gap: 8px; padding: 3px 0; font-size: 12px; cursor: pointer; }
.out-row { display: grid; grid-template-columns: 2fr 1fr; gap: 14px; }
.out-field { display: flex; flex-direction: column; gap: 5px; }
.out-field input { border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 8px; font-size: 12px; width: 100%; }
.out-ctl { display: flex; gap: 6px; align-items: center; }
.dest-preview { margin-top: 8px; }
.actions { margin-top: 14px; }
.err-box, .ok-box { font-size: 12px; border-radius: 8px; padding: 8px 12px; margin-top: 10px; }
.err-box { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
.ok-box { background: #f0fdf4; color: #15803d; border: 1px solid #bbf7d0; }
</style>
