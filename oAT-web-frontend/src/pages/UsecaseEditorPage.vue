<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Usecase Editor</div>
        <h1>{{ isEditMode ? '编辑用例' : '新建用例' }}</h1>
      </div>
      <div class="header-actions">
        <RouterLink class="secondary-link" :to="backLink">返回列表</RouterLink>
        <button class="action-button" type="button" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="status-card">正在加载编辑上下文...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <form class="editor-grid" @submit.prevent="save">
        <aside class="side-card meta-panel">
          <div class="card-title">
            <h2>元信息</h2>
          </div>

          <label class="field">
            <span>用例标题</span>
            <input v-model="form.title" class="text-input" type="text" required />
          </label>

          <label class="field">
            <span>封面图地址</span>
            <input v-model.trim="form.headImage" class="text-input" type="url" placeholder="https://..." aria-label="封面图地址" />
          </label>

          <div class="cover-preview">
            <img v-if="form.headImage" :src="form.headImage" alt="用例封面预览" />
            <div v-else class="cover-placeholder">封面预览</div>
          </div>

          <label class="field">
            <span>目录</span>
            <input v-model="form.directory" class="text-input" type="text" required />
          </label>

          <div class="field">
            <span>标签</span>
            <div class="checkbox-list">
              <label v-for="label in payload.labels" :key="label.name" class="checkbox-item">
                <input type="checkbox" :value="label.name" v-model="form.labels" />
                <span class="checkbox-label" :title="label.name">{{ label.name }}</span>
              </label>
              <div v-if="!payload.labels?.length" class="empty-hint">暂无可选标签</div>
            </div>
          </div>

          <label class="field">
            <span>缺陷</span>
            <textarea
              v-model="form.defectsText"
              class="text-area"
              rows="4"
              placeholder="每行一个缺陷 ID 或链接"
            ></textarea>
          </label>

          <label class="field">
            <span>PRD 需求</span>
            <textarea
              v-model="form.prdRequirementsText"
              class="text-area"
              rows="4"
              placeholder="每行一个 PRD ID 或链接"
            ></textarea>
          </label>
        </aside>

        <section class="content-card markdown-workbench">
          <div class="card-title markdown-toolbar">
            <div>
              <h2>Markdown 内容</h2>
              <p class="subtext">左侧编辑、右侧实时预览；保存按钮保持在首屏可见。</p>
            </div>
            <div class="toolbar-actions">
              <button class="ghost-button" type="button" @click="previewMode = previewMode === 'split' ? 'edit' : 'split'">
                {{ previewMode === 'split' ? '隐藏预览' : '显示预览' }}
              </button>
              <button class="submit-button" type="submit">保存</button>
            </div>
          </div>
          <div :class="['markdown-grid', previewMode]">
            <label class="markdown-pane editor-pane">
              <span>正文编辑</span>
              <textarea
                v-model="form.content"
                class="markdown-input"
                rows="24"
                placeholder="请输入用例正文，支持 Markdown"
                aria-label="Markdown 正文编辑"
              ></textarea>
            </label>
            <section class="markdown-pane preview-pane" aria-label="Markdown 预览">
              <div class="pane-title">实时预览</div>
              <div v-if="previewHtml" class="markdown-preview" v-html="previewHtml"></div>
              <div v-else class="empty-preview">暂无内容，输入 Markdown 后会在这里预览。</div>
            </section>
          </div>
        </section>
      </form>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useProjectStore } from '@/stores/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const usecaseId = computed(() => String(route.params.usecaseId || ''))
const isEditMode = computed(() => Boolean(usecaseId.value))
const bootstrapKey = computed(() => `${projectId.value}:${usecaseId.value || 'new'}`)
const payload = computed(() => projectStore.usecaseBootstrapByKey[bootstrapKey.value])
const backLink = computed(() => `/p/${projectId.value}/usecases`)
const loading = ref(false)
const error = ref('')
const previewMode = ref<'split' | 'edit'>('split')

const form = reactive({
  title: '',
  headImage: '',
  directory: '',
  content: '',
  labels: [] as string[],
  defectsText: '',
  prdRequirementsText: '',
})

const previewHtml = computed(() => renderMarkdown(form.content))

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function inlineMarkdown(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g, '<img src="$2" alt="$1" loading="lazy" />')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
}

function renderMarkdown(markdown: string) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n')
  const html: string[] = []
  let listOpen = false
  let codeOpen = false
  const closeList = () => {
    if (listOpen) {
      html.push('</ul>')
      listOpen = false
    }
  }
  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      closeList()
      html.push(codeOpen ? '</code></pre>' : '<pre><code>')
      codeOpen = !codeOpen
      continue
    }
    if (codeOpen) {
      html.push(`${escapeHtml(line)}\n`)
      continue
    }
    if (!line.trim()) {
      closeList()
      continue
    }
    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`)
      continue
    }
    const bullet = line.match(/^[-*]\s+(.+)$/)
    if (bullet) {
      if (!listOpen) {
        html.push('<ul>')
        listOpen = true
      }
      html.push(`<li>${inlineMarkdown(bullet[1])}</li>`)
      continue
    }
    closeList()
    html.push(`<p>${inlineMarkdown(line)}</p>`)
  }
  closeList()
  if (codeOpen) html.push('</code></pre>')
  return html.join('')
}

function syncForm() {
  if (!payload.value) {
    return
  }
  const source = payload.value.usecase
  form.title = source?.title || ''
  form.headImage = source?.headImage || ''
  form.directory = source?.directory || payload.value.currentDirectory || 'root'
  form.content = source?.content || ''
  form.labels = [...(payload.value.selectedLabelNames || [])]
  form.defectsText = payload.value.defectsText || ''
  form.prdRequirementsText = payload.value.prdRequirementsText || ''
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadUsecaseBootstrap(projectId.value, {
      directory: String(route.query.directory || 'root'),
      id: usecaseId.value || undefined,
    })
    syncForm()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载用例编辑上下文失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  loading.value = true
  error.value = ''
  try {
    const savedUsecaseId = await projectStore.persistUsecase(projectId.value, {
      id: usecaseId.value || undefined,
      title: form.title.trim(),
      headImage: form.headImage.trim() || undefined,
      content: form.content,
      directory: form.directory.trim(),
      labels: form.labels,
      defectsText: form.defectsText,
      prdRequirementsText: form.prdRequirementsText,
    })
    await router.push(`/p/${projectId.value}/usecases/${savedUsecaseId}`)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存用例失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header,
.header-actions,
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.page-header {
  margin-bottom: 20px;
}


.action-button,
.submit-button,
.ghost-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  color: #fff;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
}

.submit-button {
  background: #0f766e;
}

.ghost-button {
  background: rgba(15, 23, 42, 0.08);
  color: #334155;
}

.secondary-link {
  color: #0f766e;
  font-weight: 700;
}

.status-card,
.side-card,
.content-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.editor-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.meta-panel {
  position: sticky;
  top: 88px;
  max-height: calc(100vh - 116px);
  overflow: auto;
}

.markdown-workbench {
  min-width: 0;
}

.markdown-toolbar {
  position: sticky;
  top: 88px;
  z-index: 5;
  margin: -18px -18px 16px;
  padding: 16px 18px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 20px 20px 0 0;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(14px);
}

.subtext {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 13px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.cover-preview {
  display: grid;
  place-items: center;
  min-height: 142px;
  margin-top: 12px;
  border: 1px dashed rgba(15, 118, 110, 0.24);
  border-radius: 16px;
  background: #f8fbfb;
  overflow: hidden;
}

.cover-preview img {
  width: 100%;
  height: 156px;
  object-fit: cover;
}

.cover-placeholder {
  color: #94a3b8;
  font-weight: 700;
}

.markdown-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

.markdown-grid.edit {
  grid-template-columns: minmax(0, 1fr);
}

.markdown-grid.edit .preview-pane {
  display: none;
}

.markdown-pane {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.markdown-pane > span,
.pane-title {
  color: #64748b;
  font-size: 13px;
  font-weight: 800;
}

.preview-pane {
  align-self: stretch;
}

.markdown-preview,
.empty-preview {
  min-height: 640px;
  max-height: calc(100vh - 220px);
  overflow: auto;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  padding: 18px;
  background: #fbfdfd;
}

.empty-preview {
  display: grid;
  place-items: center;
  color: #94a3b8;
  text-align: center;
}

.field {
  display: grid;
  gap: 8px;
}

.field + .field {
  margin-top: 16px;
}

.text-input,
.text-area,
.select,
.markdown-input {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
  font: inherit;
}

.checkbox-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 8px;
  background: #fff;
  max-height: 160px;
  overflow-y: auto;
}

.checkbox-list.tall {
  max-height: 220px;
}

.checkbox-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.checkbox-item:hover {
  background: rgba(15, 118, 110, 0.06);
}

.checkbox-item input[type="checkbox"] {
  margin-top: 2px;
  flex-shrink: 0;
  accent-color: #0f766e;
  width: 15px;
  height: 15px;
  cursor: pointer;
}

.checkbox-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 13px;
}

.item-title {
  color: #1e293b;
  word-break: break-all;
  white-space: normal;
  line-height: 1.4;
}

.item-meta {
  color: #94a3b8;
  font-size: 11px;
  word-break: break-all;
  white-space: normal;
  line-height: 1.3;
}

.empty-hint {
  padding: 8px;
  color: #94a3b8;
  font-size: 12px;
  text-align: center;
}

.markdown-input {
  width: 100%;
  min-height: 640px;
  max-height: calc(100vh - 220px);
  resize: vertical;
  line-height: 1.8;
}

.markdown-preview :deep(h1),
.markdown-preview :deep(h2),
.markdown-preview :deep(h3) {
  margin: 0 0 12px;
  color: #111827;
}

.markdown-preview :deep(p),
.markdown-preview :deep(li) {
  color: #334155;
  line-height: 1.8;
}

.markdown-preview :deep(ul) {
  margin: 0 0 14px 20px;
  padding: 0;
}

.markdown-preview :deep(code) {
  border-radius: 8px;
  padding: 2px 6px;
  background: rgba(15, 23, 42, 0.08);
}

.markdown-preview :deep(pre) {
  overflow: auto;
  border-radius: 14px;
  padding: 14px;
  background: #0f172a;
  color: #e5e7eb;
}

.markdown-preview :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown-preview :deep(a) {
  color: #0f766e;
  font-weight: 800;
}

.markdown-preview :deep(img) {
  max-width: 100%;
  border-radius: 14px;
}

@media (max-width: 960px) {
  .editor-grid,
  .markdown-grid {
    grid-template-columns: 1fr;
  }

  .meta-panel,
  .markdown-toolbar {
    position: static;
    max-height: none;
  }

  .page-header,
  .header-actions,
  .card-title {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
