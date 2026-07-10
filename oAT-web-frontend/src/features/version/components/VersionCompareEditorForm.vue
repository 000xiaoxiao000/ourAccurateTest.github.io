<template>
  <div class="tab-row">
    <button type="button" :class="['tab-button', mode === 'package' && 'active']" @click="$emit('set-mode', 'package')">制品比对</button>
    <button type="button" :class="['tab-button', mode === 'git' && 'active']" :disabled="!repositoryConfigured" @click="$emit('set-mode', 'git')">Git 比对</button>
  </div>

  <form class="editor-card" @submit.prevent="$emit('submit')">
    <div v-if="mode === 'package'" class="grid-two">
      <label class="field">
        <span>旧版本文件</span>
        <select :value="packageCompare.targetFile" class="text-input" @change="updatePackageFile('target', ($event.target as HTMLSelectElement).value)">
          <option value="">请选择</option>
          <option v-for="item in packageOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select>
        <input class="hidden-input" ref="targetFileInput" type="file" accept=".jar,.war,.zip" @change="$emit('upload-package', 'target', $event)" />
        <div class="inline-actions">
          <button class="ghost-button small" type="button" :disabled="busy" @click="targetFileInput?.click()">上传旧包</button>
          <button class="danger-button small" type="button" :disabled="busy || !packageCompare.targetFile" @click="$emit('delete-package', 'target')">删除文件</button>
        </div>
      </label>
      <label class="field">
        <span>新版本文件</span>
        <select :value="packageCompare.sourceFile" class="text-input" @change="updatePackageFile('source', ($event.target as HTMLSelectElement).value)">
          <option value="">请选择</option>
          <option v-for="item in packageOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select>
        <input class="hidden-input" ref="sourceFileInput" type="file" accept=".jar,.war,.zip" @change="$emit('upload-package', 'source', $event)" />
        <div class="inline-actions">
          <button class="ghost-button small" type="button" :disabled="busy" @click="sourceFileInput?.click()">上传新包</button>
          <button class="danger-button small" type="button" :disabled="busy || !packageCompare.sourceFile" @click="$emit('delete-package', 'source')">删除文件</button>
        </div>
      </label>
    </div>

    <div v-else class="grid-two">
      <label class="field">
        <span>分支</span>
        <div class="input-action">
          <input
            :value="gitCompare.branch"
            class="text-input branch-input"
            type="text"
            list="versionCompareBranchOptions"
            placeholder="例如 master / release/1.2.x"
            :disabled="!repositoryConfigured"
            @input="updateGitField('branch', ($event.target as HTMLInputElement).value.trim())"
            @change="$emit('branch-change')"
          />
          <datalist id="versionCompareBranchOptions">
            <option v-for="branch in branchOptions" :key="branch" :value="branch" />
          </datalist>
          <button class="ghost-button small" type="button" :disabled="busy || !repositoryConfigured" @click="$emit('load-branches')">刷新</button>
        </div>
        <div v-if="branchOptions.length" class="branch-suggestions" aria-label="分支建议">
          <button
            v-for="branch in branchOptions.slice(0, 8)"
            :key="branch"
            class="branch-chip"
            type="button"
            :class="{ active: branch === gitCompare.branch }"
            :disabled="!repositoryConfigured"
            @click="$emit('select-branch', branch)"
          >
            {{ branch }}
          </button>
        </div>
        <small class="field-help">支持直接录入分支、tag 或 ref；下方建议来自应用当前分支、远端分支和已有版本记录。</small>
      </label>
      <label class="field">
        <span>旧 Commit</span>
        <div class="input-action">
          <input :value="gitCompare.oldCommit" class="text-input" type="text" :disabled="!repositoryConfigured" @input="updateGitField('oldCommit', ($event.target as HTMLInputElement).value.trim())" />
          <button class="ghost-button small" type="button" :disabled="busy || !repositoryConfigured || !gitCompare.branch" @click="$emit('open-commit-picker', 'old')">选择</button>
        </div>
      </label>
      <label class="field">
        <span>新 Commit</span>
        <div class="input-action">
          <input :value="gitCompare.newCommit" class="text-input" type="text" :disabled="!repositoryConfigured" @input="updateGitField('newCommit', ($event.target as HTMLInputElement).value.trim())" />
          <button class="ghost-button small" type="button" :disabled="busy || !repositoryConfigured || !gitCompare.branch" @click="$emit('open-commit-picker', 'new')">选择</button>
          <button class="ghost-button small" type="button" :disabled="busy || !repositoryConfigured || !gitCompare.branch" @click="$emit('fill-latest-commit')">最新</button>
        </div>
      </label>
    </div>

    <label class="field">
      <span>包范围</span>
      <input :value="packageName" class="text-input" type="text" placeholder="默认 *，例如 com.demo.order" @input="updatePackageName(($event.target as HTMLInputElement).value.trim())" />
    </label>
    <p v-if="error" class="error-text">{{ error }}</p>
    <div class="action-row">
      <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '处理中...' : '开始比对' }}</button>
      <button class="ghost-button" type="button" :disabled="busy" @click="$emit('restore-draft')">恢复上次填写</button>
      <button class="ghost-button" type="button" :disabled="busy" @click="$emit('clear-draft')">清空填写</button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'

export type VersionCompareMode = 'package' | 'git'
export type VersionPackageRole = 'source' | 'target'
export type VersionPackageCompareState = { sourceFile: string; targetFile: string }
export type VersionGitCompareState = { branch: string; oldCommit: string; newCommit: string }
export type VersionPackageOption = { value: string; label: string; uploaded: boolean }

const props = defineProps<{
  mode: VersionCompareMode
  repositoryConfigured: boolean
  busy: boolean
  packageCompare: VersionPackageCompareState
  packageOptions: VersionPackageOption[]
  gitCompare: VersionGitCompareState
  branchOptions: string[]
  packageName: string
  error: string
}>()

const emit = defineEmits<{
  (event: 'set-mode', mode: VersionCompareMode): void
  (event: 'update:packageCompare', value: VersionPackageCompareState): void
  (event: 'update:gitCompare', value: VersionGitCompareState): void
  (event: 'update:packageName', value: string): void
  (event: 'persist-draft'): void
  (event: 'restore-draft'): void
  (event: 'clear-draft'): void
  (event: 'upload-package', role: VersionPackageRole, payload: Event): void
  (event: 'delete-package', role: VersionPackageRole): void
  (event: 'load-branches'): void
  (event: 'branch-change'): void
  (event: 'select-branch', branch: string): void
  (event: 'open-commit-picker', target: 'old' | 'new'): void
  (event: 'fill-latest-commit'): void
  (event: 'submit'): void
}>()

const targetFileInput = ref<HTMLInputElement | null>(null)
const sourceFileInput = ref<HTMLInputElement | null>(null)

function updatePackageFile(role: VersionPackageRole, value: string) {
  emit('update:packageCompare', {
    ...props.packageCompare,
    [role === 'source' ? 'sourceFile' : 'targetFile']: value,
  })
  emit('persist-draft')
}

function updateGitField(field: keyof VersionGitCompareState, value: string) {
  emit('update:gitCompare', {
    ...props.gitCompare,
    [field]: value,
  })
  emit('persist-draft')
}

function updatePackageName(value: string) {
  emit('update:packageName', value)
  emit('persist-draft')
}
</script>

<style scoped>
.tab-row,
.action-row,
.inline-actions,
.input-action {
  display: flex;
  gap: 12px;
}

.inline-actions,
.input-action {
  align-items: center;
  flex-wrap: wrap;
}

.tab-button,
.primary-button,
.ghost-button,
.danger-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease;
}

.tab-button,
.ghost-button {
  background: rgba(15, 23, 42, 0.08);
}

.tab-button.active,
.primary-button {
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
  box-shadow: 0 12px 22px rgba(20, 184, 166, .22);
}

.danger-button {
  background: rgba(185, 28, 28, .12);
  color: #b91c1c;
}

.primary-button:hover:not(:disabled),
.tab-button.active:hover:not(:disabled) {
  background: linear-gradient(135deg, #0b5f59, #0f9f94);
  box-shadow: 0 14px 26px rgba(20, 184, 166, .30);
}

.ghost-button:hover:not(:disabled),
.tab-button:hover:not(:disabled) {
  background: rgba(15, 118, 110, .12);
  color: #0f766e;
}

.danger-button:hover:not(:disabled) {
  background: #b91c1c;
  color: #fff;
  box-shadow: 0 12px 22px rgba(185, 28, 28, .18);
}

.primary-button:hover:not(:disabled),
.ghost-button:hover:not(:disabled),
.danger-button:hover:not(:disabled),
.tab-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.small {
  padding: 7px 10px;
  font-size: 12px;
}

button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.editor-card {
  margin-top: 18px;
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 8px;
}

.field span {
  color: #64748b;
}

.text-input {
  width: 100%;
  min-width: 180px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  padding: 10px 12px;
}

.branch-input {
  min-width: min(360px, 100%);
}

.branch-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.branch-chip {
  border: 1px solid rgba(37, 99, 235, .18);
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(37, 99, 235, .06);
  color: #1d4ed8;
  cursor: pointer;
  font-size: 12px;
  font-weight: 800;
}

.branch-chip:hover:not(:disabled),
.branch-chip.active {
  border-color: rgba(15, 118, 110, .32);
  background: rgba(15, 118, 110, .12);
  color: #0f766e;
}

.field-help {
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.5;
}

.input-action .text-input {
  flex: 1;
}

.action-row {
  margin-top: 18px;
  flex-wrap: wrap;
}

.error-text {
  color: #b91c1c;
}

.hidden-input {
  display: none;
}

@media (max-width: 840px) {
  .grid-two {
    grid-template-columns: 1fr;
  }
}
</style>
