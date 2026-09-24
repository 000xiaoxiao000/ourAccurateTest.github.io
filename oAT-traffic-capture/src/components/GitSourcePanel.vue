<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, toRef, toRefs } from 'vue'
import type {
  GitCapability,
  GitCommit,
  GitPrepareResult,
  GitRefsResult
} from '../types/electron'
import { gitShared } from './gitPanelShared'

/**
 * 源码获取面板（内联挂在路径参数下方）：本地路径之外的第二种供给方式。
 * 形态由 git 能力档位决定：
 *   FULL    —— 本地仓库 worktree / 远程稀疏克隆 / 按分支、Tag、Commit 切版本
 *   LOCAL   —— 无 git：直接把目录当源码根（不切版本）
 *   OFFLINE —— 无 git：导入 zip / tar.gz 解压当源码根
 */
const props = defineProps<{
  paramKey: string
  cap: GitCapability | null
  /** 挂在哪个参数下：old = 旧版源码目录的面板，选中的 ref 直接回填旧版，不再出现「新版/同时拉取」 */
  target?: 'new' | 'old'
  /** 该路径参数当前已填的值：若是用户自己的目录，就把它预填成「检出到」，让源码落到那里 */
  currentValue?: string
}>()
const emit = defineEmits<{
  (e: 'apply', payload: { newSourceRoot?: string; oldSourceRoot?: string }): void
  (e: 'recheck'): void
}>()

/** 面板挂在旧版参数下时，主选择行的文案与弹窗标题都按旧版显示 */
const isOldPanel = computed(() => props.target === 'old')

// 连接信息来自跨实例共享的 gitPanelShared（新版/旧版面板只填一次地址与鉴权）
const { source, repoPath, url, authMode, username, secret, sshKeyPath, allowInsecureSsl } = toRefs(gitShared)
const branches = toRef(gitShared, 'branches')
const tags = toRef(gitShared, 'tags')

/**
 * ref 选取（新版/旧版同一套结构）：分支 → 加载 → 再按需细化到 Commit。
 * 优先级：手填 > Commit > 分支。
 */
interface RefPick { branch: string; commit: string; manual: string }
const newPick = reactive<RefPick>({ branch: '', commit: '', manual: '' })
const oldPick = reactive<RefPick>({ branch: '', commit: '', manual: '' })
const refValue = (p: RefPick) => p.manual.trim() || p.commit || p.branch
const withOld = ref(false)
/**
 * 用户是否「主动」选过 ref：loadRefs 自动填充默认分支不算。
 * 本地仓库模式下没主动选过 ⇒ 拉取并回填时直接用所选目录本身，不做检出。
 */
const newTouched = ref(false)
const oldTouched = ref(false)

const commits = ref<GitCommit[]>([])
const truncated = ref(false)
const commitsLoading = ref(false)
const commitTarget = ref<'new' | 'old'>('new')
const commitDialog = ref(false)
const commitKeyword = ref('')
const archivePath = ref('')
/** 检出落点：留空 = 应用缓存目录；填了就把源码检到用户自己的目录里 */
const outDir = ref('')
/** 密码 / 令牌明文显示开关 */
const showSecret = ref(false)

const busy = ref(false)
const logText = ref('')
const err = ref('')
const warnings = ref<string[]>([])
let offLog: (() => void) | undefined

onMounted(() => {
  offLog = window.electronAPI?.onCoverageGitLog?.((p) => { logText.value = p.text })
  // 参数框里已经填了用户自己的目录 ⇒ 把它当成源码落点（缓存目录不回填，避免自我套娃）
  const v = (props.currentValue ?? '').trim()
  if (v && !v.includes('oat-coverage')) outDir.value = v.split(';')[0].trim()
})
onUnmounted(() => { offLog?.() })

const refOptions = computed(() => [
  ...branches.value.map((b) => ({ name: b.name })),
  ...tags.value.map((t) => ({ name: t.name }))
])
const userPlaceholder = computed(() => (authMode.value === 'token' ? 'GitHub: x-access-token / GitLab: oauth2' : '登录用户名'))

/** 弹窗标题里的槽位与分支：旧版面板永远显示「旧版」+ 主选择行所选分支 */
const dialogSlot = computed(() => (isOldPanel.value || commitTarget.value === 'old') ? '旧版' : '新版')
const dialogBranch = computed(() => (commitTarget.value === 'old' && !isOldPanel.value) ? oldPick.branch : newPick.branch)

function credentials() {
  if (authMode.value === 'system') return undefined
  return {
    mode: authMode.value,
    username: username.value,
    secret: secret.value,
    sshKeyPath: sshKeyPath.value,
    allowInsecureSsl: allowInsecureSsl.value
  }
}

async function pickDir() {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择本地仓库目录' })
  if (r?.success && r.path) {
    repoPath.value = r.path
    await loadRefs()
  }
}

async function pickOutDir() {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择源码检出到哪个目录（需为空目录）' })
  if (r?.success && r.path) outDir.value = r.path
}

async function pickArchive() {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'file', title: '选择源码压缩包（zip / tar.gz）' })
  if (r?.success && r.path) archivePath.value = r.path
}

async function loadRefs(): Promise<void> {
  if (!props.cap?.available) return
  err.value = ''
  const q = { source: source.value, repoPath: repoPath.value, url: url.value, credentials: credentials() }
  const r: GitRefsResult | undefined = await window.electronAPI?.coverageGitRefs(q)
  if (!r?.success) { err.value = r?.error ?? '读取引用失败'; return }
  branches.value = r.branches
  tags.value = r.tags
  if (!newPick.branch && r.branches.length) newPick.branch = r.branches[0].name
  if (!oldPick.branch && r.branches.length) oldPick.branch = r.branches[0].name
}

async function openCommits(target: 'new' | 'old') {
  commitTarget.value = target
  commitDialog.value = true
  commitKeyword.value = ''
  await loadCommits()
}

async function loadCommits(): Promise<void> {
  if (!props.cap?.available) return
  err.value = ''
  commitsLoading.value = true
  try {
    // 提交历史跟随当前所选分支（不传 ref 的话 remote 只能拿到默认分支）
    const branch = commitTarget.value === 'new' ? newPick.branch : oldPick.branch
    const r = await window.electronAPI?.coverageGitCommits({
      source: source.value,
      repoPath: repoPath.value,
      url: url.value,
      ref: branch || undefined,
      credentials: credentials(),
      keyword: commitKeyword.value.trim() || undefined,
      limit: 200
    })
    if (!r?.success) { err.value = r?.error ?? '读取提交历史失败'; return }
    commits.value = r.commits
    truncated.value = r.truncated
  } finally {
    commitsLoading.value = false
  }
}

function chooseCommit(c: GitCommit) {
  const pick = commitTarget.value === 'new' ? newPick : oldPick
  pick.commit = c.sha
  pick.manual = ''
  if (commitTarget.value === 'new') newTouched.value = true
  else oldTouched.value = true
  commitDialog.value = false
}

/** 无 git 档：直接把目录当源码根（不切版本） */
async function useLocalDir() {
  if (!repoPath.value) { err.value = '请先选择本地目录'; return }
  busy.value = true; err.value = ''; warnings.value = []
  try {
    const r: GitPrepareResult | undefined = await window.electronAPI?.coverageGitPrepare({ mode: 'local', repoPath: repoPath.value })
    finish(r)
  } finally { busy.value = false }
}

async function doArchive() {
  if (!archivePath.value) { err.value = '请先选择压缩包'; return }
  busy.value = true; err.value = ''; warnings.value = []; logText.value = ''
  try {
    const r: GitPrepareResult | undefined = await window.electronAPI?.coverageGitPrepare({ mode: 'archive', archivePath: archivePath.value })
    finish(r)
  } finally { busy.value = false }
}

/** 本次「拉取并回填」到底会做哪种事：检出到某处，还是直接用所选目录 */
const plan = computed(() => {
  const newRefRaw = refValue(newPick)
  const oldRefRaw = refValue(oldPick)
  const useNewRef = source.value === 'remote' ? !!newRefRaw : (newTouched.value && !!newRefRaw)
  const useOldRef = source.value === 'remote'
    ? (withOld.value && !!oldRefRaw)
    : (withOld.value && oldTouched.value && !!oldRefRaw)
  return { newRefRaw, oldRefRaw, useNewRef, useOldRef, checkout: useNewRef || useOldRef }
})

/** 落点预览：点按钮之前就让用户看到源码会落到哪儿 */
const landing = computed(() => {
  if (!plan.value.checkout) {
    if (source.value === 'local' && repoPath.value) return `直接用所选目录 ${repoPath.value}（自动定位其中的 src/main/java）`
    return '先选择分支 / 提交才能拉取'
  }
  if (outDir.value) return `检出到你指定的目录 ${outDir.value}${plan.value.useOldRef ? '（旧版落在 …-old）' : ''}`
  return '检出到应用缓存目录（…/oat-coverage/git/…，不改动你的目录）'
})

async function doPrepare() {
  if (source.value === 'local' && !repoPath.value) { err.value = '请先选择本地仓库目录'; return }
  if (source.value === 'remote' && !url.value) { err.value = '请填写仓库地址'; return }
  // 远程必须选 ref；本地仓库模式：主动选过才检出，否则直接用所选目录（不检出到缓存）
  const p = plan.value
  if (source.value === 'remote' && !p.newRefRaw) { err.value = '请先选择分支 / 提交，或手填 ref'; return }
  busy.value = true; err.value = ''; warnings.value = []; logText.value = ''
  try {
    const r: GitPrepareResult | undefined = await window.electronAPI?.coverageGitPrepare({
      mode: source.value,
      repoPath: repoPath.value,
      url: url.value,
      newRef: p.useNewRef ? p.newRefRaw : undefined,
      oldRef: p.useOldRef ? p.oldRefRaw : undefined,
      outDir: p.checkout ? (outDir.value || undefined) : undefined,
      credentials: credentials()
    })
    finish(r)
  } finally { busy.value = false }
}

function finish(r?: GitPrepareResult) {
  warnings.value = r?.warnings ?? []
  if (!r?.success) { err.value = r?.error ?? '获取源码失败'; return }
  emit('apply', { newSourceRoot: r.newSourceRoot, oldSourceRoot: r.oldSourceRoot })
}
</script>

<template>
  <div class="gp">
    <div class="gp-head">
      <span>从 Git 仓库获取源码</span>
      <span v-if="cap?.available" class="gp-cap ok">✓ git {{ cap.version }} · {{ cap.bin }}</span>
      <span v-else class="gp-cap warn">⚠ 本机未检测到 git</span>
    </div>

    <!-- ===== 无 git：LOCAL / OFFLINE 档 ===== -->
    <div v-if="!cap?.available" class="gp-body">
      <p class="gp-tip">无 git 不能按版本检出，但下面两条路照样能出增量报告</p>
      <div class="gp-row">
        <span class="gp-lb">本地目录</span>
        <div class="gp-ctl">
          <input v-model="repoPath" placeholder="直接当源码根（不切版本）" />
          <button class="btn btn-outline btn-sm" @click="pickDir">选择</button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="useLocalDir">用它</button>
        </div>
      </div>
      <div class="gp-row">
        <span class="gp-lb">源码压缩包</span>
        <div class="gp-ctl">
          <input v-model="archivePath" placeholder="zip / tar.gz（新旧版各导一次）" />
          <button class="btn btn-outline btn-sm" @click="pickArchive">选择</button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="doArchive">导入</button>
        </div>
      </div>
      <p class="gp-tip">需要按 ref 切版本？先装 git 再点「重新检测」：<code>{{ cap?.installHint || 'xcode-select --install' }}</code></p>
      <button class="btn btn-outline btn-sm" @click="emit('recheck')">重新检测</button>
    </div>

    <!-- ===== 有 git：FULL 档 ===== -->
    <div v-else class="gp-body">
      <p class="gp-tip">地址与鉴权在「新版 / 旧版源码目录」的面板间共享，只需填写一次</p>
      <div class="gp-row">
        <span class="gp-lb">来源</span>
        <div class="gp-seg">
          <label :class="{ on: source === 'local' }"><input type="radio" value="local" v-model="source" @change="loadRefs" />本地仓库</label>
          <label :class="{ on: source === 'remote' }"><input type="radio" value="remote" v-model="source" />远程地址</label>
        </div>
      </div>

      <div v-if="source === 'local'" class="gp-row">
        <span class="gp-lb">仓库目录</span>
        <div class="gp-ctl">
          <input v-model="repoPath" placeholder="/Users/xx/proj/order-service" />
          <button class="btn btn-outline btn-sm" @click="pickDir">选择</button>
        </div>
      </div>
      <template v-else>
        <div class="gp-row">
          <span class="gp-lb">仓库地址</span>
          <div class="gp-ctl"><input v-model="url" placeholder="https://git.xxx.com/group/order-service.git" /></div>
        </div>
        <div class="gp-row">
          <span class="gp-lb">鉴权</span>
          <div class="gp-ctl">
            <select v-model="authMode">
              <option value="system">系统凭据（credential.helper / SSH agent）</option>
              <option value="password">用户名 + 密码</option>
              <option value="token">用户名 + 令牌</option>
              <option value="ssh">SSH 密钥</option>
            </select>
          </div>
        </div>
        <div v-if="authMode === 'password' || authMode === 'token'" class="gp-auth">
          <label>用户名<input v-model="username" :placeholder="userPlaceholder" /></label>
          <label>{{ authMode === 'token' ? '令牌' : '密码' }}
            <span class="gp-secret">
              <input :type="showSecret ? 'text' : 'password'" v-model="secret" placeholder="仅内存传递，不落盘" />
              <button class="gp-eye" type="button" :title="showSecret ? '隐藏' : '显示'" @click="showSecret = !showSecret">
                <svg v-if="showSecret" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              </button>
            </span>
          </label>
          <label class="gp-chk"><input type="checkbox" v-model="allowInsecureSsl" /> 跳过证书校验</label>
        </div>
        <div v-else-if="authMode === 'ssh'" class="gp-auth">
          <label>私钥路径（可选，留空用系统 agent）<input v-model="sshKeyPath" placeholder="~/.ssh/id_ed25519" /></label>
        </div>
      </template>

      <div class="gp-sep"></div>

      <!-- 新版（挂在旧版参数下时，这一行就是旧版本身） -->
      <div class="gp-row">
        <span class="gp-lb">{{ isOldPanel ? '旧版' : '新版' }}</span>
        <div class="gp-ctl">
          <select v-model="newPick.branch" @change="newTouched = true; newPick.commit = ''">
            <option value="" disabled>分支 / Tag</option>
            <option v-for="o in refOptions" :key="o.name" :value="o.name">{{ o.name }}</option>
          </select>
          <button class="btn btn-outline btn-sm" :disabled="busy" @click="loadRefs">加载</button>
        </div>
      </div>
      <div class="gp-row">
        <span class="gp-lb">提交</span>
        <div class="gp-ctl">
          <button class="btn btn-outline btn-sm" @click="openCommits('new')">
            {{ newPick.commit ? '重新选择' : '选择提交…' }}
          </button>
          <span v-if="newPick.commit" class="mono gp-chip">
            {{ newPick.commit.slice(0, 7) }}
            <button class="gp-x" title="清除，回到分支最新" @click="newPick.commit = ''">×</button>
          </span>
          <span v-else class="gp-mut">留空 = 分支最新</span>
        </div>
      </div>
      <div class="gp-row">
        <span class="gp-lb"></span>
        <div class="gp-ctl">
          <input v-model="newPick.manual" class="gp-manual" placeholder="或手填 ref：分支名 / Tag / SHA（优先级最高）" @input="newTouched = true" />
        </div>
      </div>

      <div v-if="!isOldPanel" class="gp-row">
        <span class="gp-lb">旧版</span>
        <label class="gp-chk"><input type="checkbox" v-model="withOld" /> 同时拉取旧版<span class="gp-mut">（可选，两版都给才能识别注释/格式行的改动）</span></label>
      </div>
      <!-- 旧版（勾选后显示，结构与新版一致） -->
      <template v-if="withOld">
        <div class="gp-row">
          <span class="gp-lb">旧版分支</span>
          <div class="gp-ctl">
            <select v-model="oldPick.branch" @change="oldTouched = true; oldPick.commit = ''">
              <option value="" disabled>分支 / Tag</option>
              <option v-for="o in refOptions" :key="o.name" :value="o.name">{{ o.name }}</option>
            </select>
          </div>
        </div>
        <div class="gp-row">
          <span class="gp-lb">提交</span>
          <div class="gp-ctl">
            <button class="btn btn-outline btn-sm" @click="openCommits('old')">
              {{ oldPick.commit ? '重新选择' : '选择提交…' }}
            </button>
            <span v-if="oldPick.commit" class="mono gp-chip">
              {{ oldPick.commit.slice(0, 7) }}
              <button class="gp-x" title="清除，回到分支最新" @click="oldPick.commit = ''">×</button>
            </span>
            <span v-else class="gp-mut">留空 = 分支最新</span>
          </div>
        </div>
        <div class="gp-row">
          <span class="gp-lb"></span>
          <div class="gp-ctl">
            <input v-model="oldPick.manual" class="gp-manual" placeholder="或手填 ref：分支名 / Tag / SHA（优先级最高）" @input="oldTouched = true" />
          </div>
        </div>
      </template>

      <div class="gp-row">
        <span class="gp-lb">检出到</span>
        <div class="gp-ctl">
          <input v-model="outDir" placeholder="留空 = 应用缓存目录；填了就把源码检到这个目录（需为空目录）" />
          <button class="btn btn-outline btn-sm" @click="pickOutDir">选择</button>
        </div>
      </div>

      <div class="gp-row gp-actions">
        <button class="btn btn-primary btn-sm" :disabled="busy" @click="doPrepare">{{ busy ? '拉取中…' : '拉取并回填' }}</button>
        <span v-if="logText" class="gp-log">{{ logText }}</span>
      </div>
      <p class="gp-land">将回填 → {{ landing }}</p>
      <p v-if="err" class="gp-err">✗ {{ err }}</p>
      <p v-for="(w, i) in warnings" :key="i" class="gp-tip warn">⚠ {{ w }}</p>
      <p v-if="!branches.length" class="gp-tip">分支 / Tag 由 ls-remote 直读（不下载任何东西）；「选择提交」只拉该分支最近 200 条记录；只有「拉取并回填」才下载源码文件</p>
      <p v-if="source === 'local' && !newTouched" class="gp-tip">未主动选分支 / Commit ⇒ 直接使用所选目录的当前内容回填，不做检出；要按版本检出再选分支或提交</p>
      <p class="gp-tip">classfiles（新版/旧版）是构建产物，git 里没有，仍需从 CI 制品或本地构建目录指定</p>
    </div>

    <!-- ===== 提交选择弹层 ===== -->
    <div v-if="commitDialog" class="gp-mask" @click.self="commitDialog = false">
      <div class="gp-dialog">
        <div class="gp-dhead">
          <span>选择提交（{{ dialogSlot }}）<template v-if="dialogBranch"> · {{ dialogBranch }}</template></span>
          <button class="co-close" @click="commitDialog = false">✕</button>
        </div>
        <div class="gp-row">
          <input v-model="commitKeyword" placeholder="按提交信息 / 作者搜索" @keyup.enter="loadCommits" />
          <button class="btn btn-outline btn-sm" :disabled="commitsLoading" @click="loadCommits">搜索</button>
        </div>
        <div v-if="commitsLoading" class="gp-loading">⏳ {{ logText || '正在获取提交历史…' }}<template v-if="logText.includes('正在获取')">（只拉当前分支的最近提交，之后走缓存）</template></div>
        <p v-if="err" class="gp-err">✗ {{ err }}</p>
        <div class="gp-list">
          <div v-for="c in commits" :key="c.sha" class="gp-citem" @click="chooseCommit(c)">
            <span class="mono">{{ c.shortSha }}</span>
            <span class="gp-cau">{{ c.author }}</span>
            <span class="gp-cdt">{{ c.date }}</span>
            <span class="gp-cmsg">{{ c.message }}</span>
          </div>
          <p v-if="!commits.length && !commitsLoading && !err" class="gp-tip">没有提交记录</p>
          <p v-if="truncated" class="gp-tip">仅显示前 {{ commits.length }} 条，请用搜索缩小范围</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gp { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; background: var(--bg-soft, #fafafa); padding: 12px 14px; margin: 6px 0 2px; }
.gp-head { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; padding-bottom: 8px; border-bottom: 1px dashed var(--border, #e5e7eb); }
.gp-cap { font-size: 12px; font-weight: 400; }
.gp-cap.ok { color: #15803d; }
.gp-cap.warn { color: #b45309; }
.gp-body { margin-top: 10px; }
/* 行布局：72px 标签列 + 控件区，所有行左缘对齐 */
.gp-row { display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.gp-lb { flex: 0 0 72px; font-size: 12px; color: var(--text-soft, #6b7280); }
.gp-ctl { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; flex-wrap: wrap; }
.gp-ctl input, .gp-ctl select { flex: 1 1 160px; min-width: 0; }
.gp-mut { font-size: 12px; color: var(--text-soft, #6b7280); }
/* 来源：segmented 单选 */
.gp-seg { display: inline-flex; border: 1px solid var(--border, #d1d5db); border-radius: 8px; overflow: hidden; background: var(--bg, #fff); }
.gp-seg label { display: inline-flex; align-items: center; padding: 4px 16px; font-size: 12px; cursor: pointer; color: var(--text-soft, #6b7280); user-select: none; }
.gp-seg label + label { border-left: 1px solid var(--border, #d1d5db); }
.gp-seg label.on { background: var(--primary-soft, #e0e7ff); color: var(--primary, #3b5bdb); font-weight: 500; }
.gp-seg input { display: none; }
.gp-sep { border-top: 1px dashed var(--border, #e5e7eb); margin: 10px 0; }
.gp-auth { display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; margin: 4px 0 4px 80px; }
.gp-auth label { display: flex; align-items: center; gap: 4px; color: var(--text-soft, #6b7280); }
.gp-auth input { min-width: 150px; }
.gp-secret { display: inline-flex; align-items: center; position: relative; }
.gp-secret input { padding-right: 26px; }
.gp-eye { position: absolute; right: 4px; display: inline-flex; align-items: center; border: none; background: none; cursor: pointer; padding: 2px; color: var(--text-soft, #6b7280); }
.gp-eye:hover { color: var(--text, #374151); }
.gp-chk { font-size: 12px; display: inline-flex; align-items: center; gap: 5px; cursor: pointer; color: var(--text, #374151); }
.gp-manual { font-size: 12px; }
.gp-chip { display: inline-flex; align-items: center; gap: 4px; padding: 1px 6px; border-radius: 6px; background: var(--primary-soft, #e0e7ff); font-size: 12px; }
.gp-x { border: none; background: none; cursor: pointer; font-size: 13px; color: var(--text-soft, #6b7280); padding: 0 2px; }
.gp-actions { margin-top: 10px; }
.gp-land { font-size: 12px; color: var(--primary, #3b5bdb); background: var(--primary-soft, #eef2ff); border-radius: 6px; padding: 4px 8px; margin: 6px 0; word-break: break-all; }
.gp-loading { font-size: 12px; color: var(--text-soft, #6b7280); margin: 6px 0 2px; }
.gp-tip { font-size: 12px; color: var(--text-soft, #6b7280); line-height: 1.7; margin: 6px 0; }
.gp-tip.warn { color: #b45309; }
.gp-err { font-size: 12px; color: #b91c1c; margin: 4px 0; }
.gp-log { font-size: 12px; color: var(--text-soft, #6b7280); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gp-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.25); display: flex; align-items: center; justify-content: center; z-index: 20; }
.gp-dialog { width: 620px; max-width: 92%; background: var(--bg, #fff); border-radius: 10px; padding: 12px; box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18); }
.gp-dhead { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.gp-list { max-height: 320px; overflow: auto; margin-top: 6px; }
.gp-citem { display: flex; gap: 10px; align-items: baseline; padding: 5px 6px; border-radius: 6px; cursor: pointer; font-size: 12px; }
.gp-citem:hover { background: var(--bg-soft, #f3f4f6); }
.gp-cau { color: var(--text-soft, #6b7280); }
.gp-cdt { color: var(--text-soft, #6b7280); }
.gp-cmsg { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
