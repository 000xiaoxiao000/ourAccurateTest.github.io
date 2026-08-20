<template>
  <section class="git-impact-page">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">Git Change Impact</div>
        <h1>Git 变更影响分析</h1>
        <p class="subtext">比较两个 Commit，定位结构化变更、调用传播路径，以及受影响的验收标准和回归用例。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadOverview" />
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <section class="panel-section form-stack">
      <div class="section-head"><h2>分析范围</h2><span>确定性传播优先，LLM 仅辅助确认</span></div>
      <label>
        <span>分析基线</span>
        <select v-model="form.baselineId">
          <option value="">请选择已完成的 AI 验证基线</option>
          <option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">{{ baseline.name }} · {{ baselineStatusText(baseline.status) }}</option>
        </select>
      </label>
      <div class="inline-grid">
        <label>
          <span>源码工程</span>
          <select v-model="form.appId" @change="onAppChange">
            <option value="">请选择源码工程</option>
            <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
          </select>
        </label>
        <label class="branch-cell">
          <span>分支</span>
          <div class="branch-control">
            <select
              v-model="form.branch"
              :disabled="!form.appId || branchLoading"
              @change="onBranchChange"
            >
              <option value="">
                {{ !form.appId ? '请先选择源码工程' : branchLoading ? '加载中…' : '请选择分支' }}
              </option>
              <option v-for="branch in branchOptions" :key="branch" :value="branch">{{ branch }}</option>
            </select>
            <button
              type="button"
              class="icon-button"
              :disabled="!form.appId || !form.branch || commitsLoading"
              :title="commitsLoading ? '正在加载 Commit' : '刷新最近 Commits'"
              aria-label="刷新最近 Commits"
              @click="refreshCommits"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" :class="commitsLoading ? 'spinning' : ''">
                <path d="M4 4v6h6M20 20v-6h-6M5 13a8 8 0 0 0 14.9 4M19 11a8 8 0 0 0-14.9-4" />
              </svg>
            </button>
            <button
              type="button"
              class="icon-button repo-link"
              :disabled="!form.appId || !repoBaseUrl"
              :title="repoBaseUrl ? '在代码仓库中查看分支' : '当前工程尚未配置仓库地址'"
              aria-label="在代码仓库中查看"
              @click="openRepoRoot"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M14 3h7v7M10 14L21 3M21 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5" />
              </svg>
            </button>
          </div>
        </label>
        <div class="latest-hint">
          <span>最新 Commit</span>
          <code v-if="latestCommit" :title="latestCommit">{{ shortCommit(latestCommit) }}</code>
          <button
            v-else-if="form.appId && form.branch && !latestCommitLoading"
            type="button"
            class="ghost-button"
            @click="fetchLatestCommit"
          >点击获取</button>
          <span v-else-if="latestCommitLoading" class="muted">加载中…</span>
          <span v-else class="muted">选择源码工程与分支后自动获取</span>
        </div>
      </div>

      <div class="commits-grid">
        <div class="commit-field" :class="{ expanded: picker === 'base' }">
          <div class="commit-field-head">
            <span class="commit-label">Base Commit</span>
            <div class="commit-field-actions">
              <button
                type="button"
                class="ghost-button"
                :disabled="!latestCommit"
                @click="useLatest('base')"
              >用最新</button>
              <button
                type="button"
                class="ghost-button"
                :disabled="!repoBaseUrl || !form.baseCommit"
                :title="form.baseCommit ? '在代码仓库查看该 Commit' : '请先选择 Commit'"
                @click="openRepoAtCommit('base')"
              >在仓库查看 ↗</button>
            </div>
          </div>
          <div class="commit-input-wrap">
            <input
              ref="baseInput"
              class="commit-input"
              type="text"
              v-model.trim="form.baseCommit"
              placeholder="键入 Commit Id，或从下拉中选择"
              autocomplete="off"
              spellcheck="false"
              @focus="togglePicker('base', true)"
              @input="filterPicker('base')"
            />
            <button
              type="button"
              class="commit-popover-trigger"
              :disabled="!commitOptions.length"
              :title="commitOptions.length ? '在最近 Commit 中选择' : '当前分支暂无可枚举的 Commit'"
              :aria-expanded="picker === 'base'"
              aria-label="选择 Recent Commit"
              @click="togglePicker('base')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" :class="{ open: picker === 'base' }"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
            </button>
            <span v-if="commitShort(form.baseCommit)" class="commit-preview">{{ commitShort(form.baseCommit) }}</span>
          </div>
          <Teleport to="body">
            <div
              v-if="picker === 'base'"
              ref="basePopover"
              class="commit-popover"
              :style="popoverStyle.base"
              @mousedown.stop
            >
              <div class="commit-popover-head">
                <span class="commit-popover-title">最近 {{ filteredBaseCommits.length || commitOptions.length }} 个 Commit</span>
                <input
                  v-model.trim="baseFilter"
                  type="text"
                  class="commit-popover-filter"
                  placeholder="按消息、作者或 Commit Id 过滤"
                  spellcheck="false"
                  autocomplete="off"
                />
              </div>
              <div class="commit-popover-body">
                <div v-if="commitsLoading" class="empty-inline">正在读取 Commit…</div>
                <div v-else-if="!commitOptions.length" class="empty-inline">当前分支暂无可枚举的 Commit，可手动键入。</div>
                <div v-else-if="!filteredBaseCommits.length" class="empty-inline">没有匹配「{{ baseFilter }}」的 Commit。</div>
                <button
                  v-for="c in filteredBaseCommits"
                  :key="`browse-base-${c.commitId}`"
                  type="button"
                  class="commit-picker-row"
                  :class="{ active: form.baseCommit === c.commitId }"
                  @click="pickCommit('base', c)"
                >
                  <div class="commit-line-main">
                    <code class="commit-sha">{{ c.shortCommitId || (c.commitId || '').slice(0, 8) }}</code>
                    <span class="commit-message" :title="c.message">{{ c.message || '（无 Commit 信息）' }}</span>
                    <time class="commit-time" :title="`commit 时间：${c.commitTimeText || '未知'}`">{{ c.commitTimeText || '—' }}</time>
                  </div>
                  <div class="commit-line-meta">
                    <span class="commit-author">
                      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" width="12" height="12"><path fill="currentColor" d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm0 2c-3.31 2-6 2.69-6 5v1h12v-1c0-2.31-2.69-3-6-5Z"/></svg>
                      {{ c.author || '未知作者' }}
                    </span>
                    <span class="commit-full" :title="c.commitId">{{ c.commitId }}</span>
                  </div>
                </button>
                <div class="empty-inline" v-if="commitsLoading">正在读取更多 Commit…</div>
              </div>
              <div v-if="commitOptions.length" class="commit-popover-foot">
                <span class="commit-popover-summary">
                  已显示 <strong>{{ commitOptions.length }}</strong> 个 Commit
                  <template v-if="commitsReachedTail">· 已到分支顶端</template>
                </span>
                <span class="commit-popover-summary">
                  <template v-if="moreLoading">加载下一页中…</template>
                  <template v-else-if="!commitsReachedTail">滚动到底自动加载</template>
                </span>
              </div>
              <div
                v-if="commitOptions.length && !commitsReachedTail"
                ref="baseSentinel"
                class="commit-popover-sentinel"
                aria-hidden="true"
              >
                <span class="dot-loader"></span>
                <span>滚动到底自动加载 {{ COMMIT_PICKER_PAGE }} 个 Commit…</span>
              </div>
              <div v-else-if="commitsReachedTail" class="commit-popover-sentinel commit-popover-tail">
                — 已到分支顶端 —
              </div>
            </div>
          </Teleport>
        </div>

        <div class="commit-field" :class="{ expanded: picker === 'head' }">
          <div class="commit-field-head">
            <span class="commit-label">Head Commit</span>
            <div class="commit-field-actions">
              <button
                type="button"
                class="ghost-button"
                :disabled="!latestCommit"
                @click="useLatest('head')"
              >用最新</button>
              <button
                type="button"
                class="ghost-button"
                :disabled="!repoBaseUrl || !form.headCommit"
                :title="form.headCommit ? '在代码仓库查看该 Commit' : '请先选择 Commit'"
                @click="openRepoAtCommit('head')"
              >在仓库查看 ↗</button>
            </div>
          </div>
          <div class="commit-input-wrap">
            <input
              ref="headInput"
              class="commit-input"
              type="text"
              v-model.trim="form.headCommit"
              placeholder="键入 Commit Id，或从下拉中选择"
              autocomplete="off"
              spellcheck="false"
              @focus="togglePicker('head', true)"
              @input="filterPicker('head')"
            />
            <button
              type="button"
              class="commit-popover-trigger"
              :disabled="!commitOptions.length"
              :title="commitOptions.length ? '在最近 Commit 中选择' : '当前分支暂无可枚举的 Commit'"
              :aria-expanded="picker === 'head'"
              aria-label="选择 Recent Commit"
              @click="togglePicker('head')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" :class="{ open: picker === 'head' }"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
            </button>
            <span v-if="commitShort(form.headCommit)" class="commit-preview">{{ commitShort(form.headCommit) }}</span>
          </div>
          <Teleport to="body">
            <div
              v-if="picker === 'head'"
              ref="headPopover"
              class="commit-popover"
              :style="popoverStyle.head"
              @mousedown.stop
            >
              <div class="commit-popover-head">
                <span class="commit-popover-title">最近 {{ filteredHeadCommits.length || commitOptions.length }} 个 Commit</span>
                <input
                  v-model.trim="headFilter"
                  type="text"
                  class="commit-popover-filter"
                  placeholder="按消息、作者或 Commit Id 过滤"
                  spellcheck="false"
                  autocomplete="off"
                />
              </div>
              <div class="commit-popover-body">
                <div v-if="commitsLoading" class="empty-inline">正在读取 Commit…</div>
                <div v-else-if="!commitOptions.length" class="empty-inline">当前分支暂无可枚举的 Commit，可手动键入。</div>
                <div v-else-if="!filteredHeadCommits.length" class="empty-inline">没有匹配「{{ headFilter }}」的 Commit。</div>
                <button
                  v-for="c in filteredHeadCommits"
                  :key="`browse-head-${c.commitId}`"
                  type="button"
                  class="commit-picker-row"
                  :class="{ active: form.headCommit === c.commitId }"
                  @click="pickCommit('head', c)"
                >
                  <div class="commit-line-main">
                    <code class="commit-sha">{{ c.shortCommitId || (c.commitId || '').slice(0, 8) }}</code>
                    <span class="commit-message" :title="c.message">{{ c.message || '（无 Commit 信息）' }}</span>
                    <time class="commit-time" :title="`commit 时间：${c.commitTimeText || '未知'}`">{{ c.commitTimeText || '—' }}</time>
                  </div>
                  <div class="commit-line-meta">
                    <span class="commit-author">
                      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" width="12" height="12"><path fill="currentColor" d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm0 2c-3.31 2-6 2.69-6 5v1h12v-1c0-2.31-2.69-3-6-5Z"/></svg>
                      {{ c.author || '未知作者' }}
                    </span>
                    <span class="commit-full" :title="c.commitId">{{ c.commitId }}</span>
                  </div>
                </button>
                <div class="empty-inline" v-if="commitsLoading">正在读取更多 Commit…</div>
              </div>
              <div v-if="commitOptions.length" class="commit-popover-foot">
                <span class="commit-popover-summary">
                  已显示 <strong>{{ commitOptions.length }}</strong> 个 Commit
                  <template v-if="commitsReachedTail">· 已到分支顶端</template>
                </span>
                <span class="commit-popover-summary">
                  <template v-if="moreLoading">加载下一页中…</template>
                  <template v-else-if="!commitsReachedTail">滚动到底自动加载</template>
                </span>
              </div>
              <div
                v-if="commitOptions.length && !commitsReachedTail"
                ref="headSentinel"
                class="commit-popover-sentinel"
                aria-hidden="true"
              >
                <span class="dot-loader"></span>
                <span>滚动到底自动加载 {{ COMMIT_PICKER_PAGE }} 个 Commit…</span>
              </div>
              <div v-else-if="commitsReachedTail" class="commit-popover-sentinel commit-popover-tail">
                — 已到分支顶端 —
              </div>
            </div>
          </Teleport>
        </div>
      </div>
      <div class="analyze-action-row">
        <button
          type="button"
          class="analyze-button"
          :disabled="loading || !canAnalyze"
          :aria-label="loading ? '正在分析 Git 影响范围' : '分析 Git 影响范围'"
          @click="analyze"
        >
          <svg v-if="loading" class="analyze-icon spinning" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20 11a8 8 0 0 0-14.9-4L3 9m0-5v5h5M4 13a8 8 0 0 0 14.9 4L21 15m0 5v-5h-5" />
          </svg>
          <span>{{ loading ? '正在分析影响范围...' : '分析 Git 影响范围' }}</span>
          <span v-if="!canAnalyze && !loading" class="analyze-hint">请完善分析条件</span>
        </button>
      </div>
      <div v-if="analysisJob" :class="['analysis-progress', analysisJob.status.toLowerCase()]">
        <div class="progress-top">
          <strong>{{ analysisJob.message || jobStatusText }}</strong>
          <span>{{ analysisJob.percent }}%</span>
        </div>
        <div class="progress-track"><div :style="{ width: `${analysisJob.percent}%` }"></div></div>
        <div class="progress-meta">
          <span>{{ stageText(analysisJob.stage) }}</span>
          <span>{{ jobStatusText }}</span>
        </div>
      </div>
    </section>

    <section v-if="result" class="panel-section result-panel">
      <div class="section-head">
        <h2>影响结果</h2>
        <span>{{ shortCommit(result.report.changeSet.baseCommit) }} → {{ shortCommit(result.report.changeSet.headCommit) }}</span>
      </div>

      <div class="summary-grid">
        <div class="summary-item"><strong>{{ result.report.changeSet.files.length }}</strong><span>变更文件</span></div>
        <div class="summary-item">
          <strong>{{ result.report.directChanges.length }}</strong>
          <span class="summary-help" title="直接结构变更：本次 Git diff 中实际发生新增、修改或删除的代码结构元素，如类、方法、字段等。">直接结构变更</span>
        </div>
        <div class="summary-item">
          <strong>{{ transitiveCandidates.length }}</strong>
          <span class="summary-help" title="传播候选：由直接代码变更沿调用、依赖、继承或引用关系推导出的潜在受影响对象，需要进一步确认是否真的受影响。">传播候选</span>
        </div>
        <div class="summary-item"><strong>{{ result.traceability.affectedCriteria.length }}</strong><span>验收标准</span></div>
        <div class="summary-item"><strong>{{ result.traceability.affectedTestcases.length }}</strong><span>回归用例</span></div>
      </div>

      <div class="commit-strip">
        <div><span>Base</span><code>{{ result.report.changeSet.baseCommit }}</code></div>
        <div><span>Head</span><code>{{ result.report.changeSet.headCommit }}</code></div>
        <div><span>分析器</span><code>{{ result.report.changeSet.analyzerVersion || 'unknown' }}</code></div>
      </div>

      <div v-if="llmReview" :class="['llm-status', llmReview.status.toLowerCase()]">
        <strong>LLM 辅助确认：{{ llmReviewText }}</strong>
        <span v-if="llmReview.total">{{ llmReview.completed }} / {{ llmReview.total }} 条</span>
        <span v-if="llmReview.message">{{ llmReview.message }}</span>
      </div>

      <div class="result-toolbar">
        <label>
          <span>关键字</span>
          <input v-model.trim="resultFilters.keyword" placeholder="筛选文件、符号、路径或用例" />
        </label>
        <label>
          <span>传播类型</span>
          <select v-model="resultFilters.classification">
            <option value="">全部候选</option>
            <option value="TRANSITIVE">确定传播</option>
            <option value="POSSIBLE">可能影响</option>
            <option value="UNKNOWN">待确认</option>
          </select>
        </label>
      </div>

      <div v-if="!result.report.directChanges.length" class="empty-state compact">两个 Commit 间未发现可分析的结构化源码变更。</div>

      <div v-else class="result-layout">
        <section class="result-block files-block">
          <div class="block-head">
            <h3>文件变更</h3>
            <span>{{ filteredFiles.length }} / {{ result.report.changeSet.files.length }} 个文件</span>
          </div>
          <div v-if="!filteredFiles.length" class="empty-inline">没有匹配的文件变更。</div>
          <article v-for="file in paginatedFiles" :key="`${file.oldPath}-${file.newPath}`" class="file-row">
            <div class="row-main">
              <span :class="['badge', badgeClass(file.changeType)]">{{ typeText(file.changeType) }}</span>
              <strong>{{ filePath(file) }}</strong>
            </div>
            <div class="meta-line">
              <span>{{ file.language || 'unknown' }}</span>
              <span v-if="file.renameScore">rename {{ file.renameScore }}%</span>
              <span v-if="formatRanges(file.oldRanges)">旧行 {{ formatRanges(file.oldRanges) }}</span>
              <span v-if="formatRanges(file.newRanges)">新行 {{ formatRanges(file.newRanges) }}</span>
            </div>
          </article>
          <AppPagination
            v-model:page="pages.files"
            v-model:page-size="pageSizes.files"
            :total="filteredFiles.length"
            item-name="文件"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block direct-block">
          <div class="block-head">
            <h3>直接结构变更</h3>
            <span>{{ filteredDirectChanges.length }} / {{ result.report.directChanges.length }} 项</span>
          </div>
          <div v-if="!filteredDirectChanges.length" class="empty-inline">没有匹配的结构变更。</div>
          <article v-for="change in paginatedDirectChanges" :key="changeKey(change)" class="change-card">
            <div class="change-title">
              <span :class="['badge', badgeClass(change.changeType)]">{{ typeText(change.changeType) }}</span>
              <div>
                <strong>{{ symbolName(changeKey(change)) }}</strong>
                <span>{{ symbolMeta(change) }}</span>
              </div>
            </div>
            <div class="facet-list">
              <span v-for="facet in change.facets" :key="facet">{{ facetText(facet) }}</span>
            </div>
            <div class="meta-line">
              <span v-if="activeSymbol(change)?.path">{{ activeSymbol(change)?.path }}</span>
              <span v-if="rangeText(activeSymbol(change)?.range)">行 {{ rangeText(activeSymbol(change)?.range) }}</span>
              <span v-if="formatRanges(change.evidenceRanges)">变更行 {{ formatRanges(change.evidenceRanges) }}</span>
            </div>
            <details v-if="activeSymbol(change)?.snippet" class="snippet-box">
              <summary>查看源码片段</summary>
              <div class="snippet-code" role="region" aria-label="源码片段">
                <div v-for="line in snippetLines(change)" :key="line.key" class="snippet-line">
                  <span class="snippet-line-number">{{ line.number }}</span>
                  <code>{{ line.text || ' ' }}</code>
                </div>
              </div>
            </details>
          </article>
          <AppPagination
            v-model:page="pages.direct"
            v-model:page-size="pageSizes.direct"
            :total="filteredDirectChanges.length"
            item-name="变更"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block candidates-block">
          <div class="block-head">
            <h3>传播影响路径</h3>
            <span>{{ filteredCandidates.length }} / {{ transitiveCandidates.length }} 条候选</span>
          </div>
          <div v-if="!filteredCandidates.length" class="empty-inline">没有匹配的传播影响。</div>
          <article v-for="candidate in paginatedCandidates" :key="candidateKey(candidate)" class="candidate-card">
            <div class="candidate-top">
              <span :class="['badge', classificationClass(candidate.classification)]">{{ classificationText(candidate.classification) }}</span>
              <strong>{{ symbolName(candidate.targetSymbol) }}</strong>
              <span class="confidence">{{ percent(candidate.confidence) }}</span>
            </div>
            <div class="meta-line">
              <span>{{ directionText(candidate.direction) }}</span>
              <span>{{ candidate.distance }} 跳</span>
              <span>{{ reasonText(candidate.reason) }}</span>
            </div>
            <div v-if="candidate.path?.symbols?.length" class="path-chain">
              <template v-for="(symbol, index) in candidate.path.symbols" :key="`${candidateKey(candidate)}-${index}`">
                <span>{{ symbolName(symbol) }}</span>
                <b v-if="index < candidate.path.symbols.length - 1">{{ candidate.path.edgeTypes?.[index] || '→' }}</b>
              </template>
            </div>
            <p v-if="llmJudgement(candidate)" class="llm-note">
              LLM：{{ llmText(llmJudgement(candidate)?.decision) }} · {{ percent(llmJudgement(candidate)?.confidence || 0) }}
            </p>
          </article>
          <AppPagination
            v-model:page="pages.candidates"
            v-model:page-size="pageSizes.candidates"
            :total="filteredCandidates.length"
            item-name="候选"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block trace-block">
          <div class="block-head">
            <h3>业务追溯映射</h3>
            <span>{{ result.traceability.affectedSymbols.length }} 个命中符号</span>
          </div>
          <div v-if="result.traceability.affectedSymbols.length" class="symbol-hit-strip">
            <span v-for="symbol in visibleAffectedSymbols" :key="symbol" :title="symbol">{{ symbolName(symbol) }}</span>
            <b v-if="hiddenAffectedSymbolCount > 0">+{{ hiddenAffectedSymbolCount }}</b>
          </div>
          <div
            v-if="result.traceability.affectedSymbols.length && !result.traceability.affectedCriteria.length && !result.traceability.affectedTestcases.length"
            class="empty-inline trace-warning"
          >
            已识别代码影响符号，但当前基线没有匹配到验收标准或测试用例追溯链接。
          </div>
          <div class="trace-columns">
            <div>
              <h4>受影响验收标准</h4>
              <div v-if="!filteredCriteria.length" class="empty-inline">暂无匹配的验收标准。</div>
              <details v-for="criterion in paginatedCriteria" :key="criterion.id" class="trace-card trace-item-detail">
                <summary>
                  <span>
                    <strong>{{ criterion.requirementKey }} / {{ criterion.acKey }}</strong>
                    <em>{{ criterion.title || criterion.content }}</em>
                  </span>
                  <b>详情</b>
                </summary>
                <div class="trace-inline-detail">
                  <p>{{ criterion.content || criterion.title || '暂无内容' }}</p>
                  <div class="detail-meta">
                    <span v-if="criterion.priority">优先级 {{ criterion.priority }}</span>
                    <span>{{ criterion.testable ? '可测试' : '不可测试' }}</span>
                    <span v-if="criterion.ambiguity">存在歧义</span>
                    <span>置信度 {{ percent(criterion.confidence) }}</span>
                    <span v-if="criterion.sourceLocator">{{ criterion.sourceLocator }}</span>
                  </div>
                </div>
              </details>
              <AppPagination
                v-model:page="pages.criteria"
                v-model:page-size="pageSizes.criteria"
                :total="filteredCriteria.length"
                item-name="标准"
                :page-sizes="[5, 10, 20]"
              />
            </div>
            <div>
              <h4>建议回归用例</h4>
              <div v-if="!filteredTestcases.length" class="empty-inline">暂无匹配的测试用例。</div>
              <details v-for="testcase in paginatedTestcases" :key="testcase.id" class="trace-card trace-item-detail">
                <summary>
                  <span>
                    <strong>{{ testcase.externalKey }}</strong>
                    <em>{{ testcase.title }}</em>
                  </span>
                  <b>详情</b>
                </summary>
                <div class="trace-inline-detail">
                  <dl>
                    <template v-if="testcase.requirementRefs"><dt>关联需求</dt><dd>{{ testcase.requirementRefs }}</dd></template>
                    <template v-if="testcase.preconditions"><dt>前置条件</dt><dd>{{ testcase.preconditions }}</dd></template>
                    <template v-if="testcase.steps"><dt>执行步骤</dt><dd>{{ testcase.steps }}</dd></template>
                    <template v-if="testcase.testData"><dt>测试数据</dt><dd>{{ testcase.testData }}</dd></template>
                    <template v-if="testcase.expected"><dt>预期结果</dt><dd>{{ testcase.expected }}</dd></template>
                    <template v-if="testcase.sourceLocator"><dt>来源定位</dt><dd>{{ testcase.sourceLocator }}</dd></template>
                  </dl>
                </div>
              </details>
              <AppPagination
                v-model:page="pages.testcases"
                v-model:page-size="pageSizes.testcases"
                :total="filteredTestcases.length"
                item-name="用例"
                :page-sizes="[5, 10, 20]"
              />
            </div>
          </div>
        </section>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { fetchGitChangeImpactJob, fetchGitImpactLlmReview, fetchVerificationOverview, startGitChangeImpactJob, type GitChangeImpactResponse, type GitImpactAnalysisJob, type GitImpactLlmReviewProgress, type GitImpactReport, type VerificationOverview } from '@/api/verification'
import { fetchRepositoryBranches, fetchRepositoryConfig } from '@/api/bootstrap'
import { fetchGitLatestCommit, fetchGitRecentCommits } from '@/api/version'
import type { GitCommitOption, RepositoryConfigPayload } from '@/api/types'
import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useToast } from '@/composables/useToast'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const toast = useToast()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.contextByProjectId[projectId.value]?.apps || [])
const overview = ref<VerificationOverview>({ requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] })
const result = ref<GitChangeImpactResponse | null>(null)
const analysisJob = ref<GitImpactAnalysisJob | null>(null)
const llmReview = ref<GitImpactLlmReviewProgress | null>(null)
let analysisPollTimer: number | undefined
let llmPollTimer: number | undefined
const loading = ref(false)
const error = ref('')
const form = reactive({ baselineId: '', appId: '', branch: '', baseCommit: '', headCommit: '' })
const resultFilters = reactive({ keyword: '', classification: '' })
const pages = reactive({ files: 1, direct: 1, candidates: 1, criteria: 1, testcases: 1 })
const pageSizes = reactive({ files: 10, direct: 10, candidates: 10, criteria: 5, testcases: 5 })

// Git commit picker state
const branchOptions = ref<string[]>([])
const branchLoading = ref(false)
const commitOptions = ref<GitCommitOption[]>([])
const commitsLoading = ref(false)
const moreLoading = ref(false)
const commitsLimit = ref(50)
const commitsReachedTail = ref(true)
const latestCommit = ref('')
const latestCommitLoading = ref(false)
const repoConfig = ref<RepositoryConfigPayload | null>(null)
const picker = ref<'' | 'base' | 'head'>('')
const baseFilter = ref('')
const headFilter = ref('')
const baseInput = ref<HTMLInputElement | null>(null)
const headInput = ref<HTMLInputElement | null>(null)
const basePopover = ref<HTMLDivElement | null>(null)
const headPopover = ref<HTMLDivElement | null>(null)
const baseSentinel = ref<HTMLDivElement | null>(null)
const headSentinel = ref<HTMLDivElement | null>(null)
const popoverStyle = reactive<{ base: Record<string, string>; head: Record<string, string> }>({
  base: { top: '0px', left: '0px', width: '0px' },
  head: { top: '0px', left: '0px', width: '0px' },
})
let baseLazyObserver: IntersectionObserver | null = null
let headLazyObserver: IntersectionObserver | null = null
const canAnalyze = computed(() => !!form.baselineId && !!form.appId && !!form.baseCommit && !!form.headCommit)
const selectedApp = computed(() => apps.value.find(app => app.id === form.appId))
const repoBaseUrl = computed(() => repoWebUrl(repoConfig.value?.app?.repoAddress))
const transitiveCandidates = computed(() => (result.value?.report.candidates || [])
  .filter(candidate => candidate.classification !== 'DIRECT')
  .sort((a, b) => b.confidence - a.confidence || a.distance - b.distance))
const llmReviewText = computed(() => {
  if (!llmReview.value) return '未开始'
  return ({
    PENDING: '排队中',
    RUNNING: '后台审阅中',
    COMPLETED: '已完成',
    FAILED: '失败',
    UNAVAILABLE: '不可用',
    NOT_FOUND: '未找到',
  } as Record<string, string>)[llmReview.value.status] || llmReview.value.status
})
const jobStatusText = computed(() => {
  if (!analysisJob.value) return '未开始'
  return ({ PENDING: '排队中', RUNNING: '分析中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[analysisJob.value.status] || analysisJob.value.status
})

function baselineStatusText(value?: string) {
  return ({
    CREATED: '待分析',
    ANALYZING: '分析中',
    WAITING_REVIEW: '待人工确认',
    COMPLETED: '已完成',
    FAILED: '分析失败',
    STALE: '已过期',
  } as Record<string, string>)[value || ''] || value || '-'
}

const keyword = computed(() => resultFilters.keyword.toLowerCase())
const filteredFiles = computed(() => (result.value?.report.changeSet.files || []).filter(file => matchesKeyword([filePath(file), file.changeType, file.language])))
const filteredDirectChanges = computed(() => (result.value?.report.directChanges || []).filter(change => matchesKeyword([
  changeKey(change),
  change.changeType,
  change.facets.join(' '),
  activeSymbol(change)?.path,
  activeSymbol(change)?.qualifiedName,
  activeSymbol(change)?.signature,
])))
const filteredCandidates = computed(() => transitiveCandidates.value.filter(candidate => {
  const matchesType = !resultFilters.classification || candidate.classification === resultFilters.classification
  return matchesType && matchesKeyword([candidate.seedSymbol, candidate.targetSymbol, candidate.classification, candidate.direction, candidate.reason])
}))
const filteredCriteria = computed(() => (result.value?.traceability.affectedCriteria || []).filter(criterion => matchesKeyword([
  criterion.requirementKey,
  criterion.acKey,
  criterion.title,
  criterion.content,
])))
const filteredTestcases = computed(() => (result.value?.traceability.affectedTestcases || []).filter(testcase => matchesKeyword([
  testcase.externalKey,
  testcase.title,
])))
const paginatedFiles = computed(() => paginate(filteredFiles.value, pages.files, pageSizes.files))
const paginatedDirectChanges = computed(() => paginate(filteredDirectChanges.value, pages.direct, pageSizes.direct))
const paginatedCandidates = computed(() => paginate(filteredCandidates.value, pages.candidates, pageSizes.candidates))
const paginatedCriteria = computed(() => paginate(filteredCriteria.value, pages.criteria, pageSizes.criteria))
const paginatedTestcases = computed(() => paginate(filteredTestcases.value, pages.testcases, pageSizes.testcases))
const visibleAffectedSymbols = computed(() => (result.value?.traceability.affectedSymbols || []).slice(0, 8))
const hiddenAffectedSymbolCount = computed(() => Math.max(0, (result.value?.traceability.affectedSymbols.length || 0) - visibleAffectedSymbols.value.length))

async function loadOverview() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectContext(projectId.value)
    overview.value = await fetchVerificationOverview(projectId.value)
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function analyze() {
  if (loading.value) return
  if (!canAnalyze.value) return
  loading.value = true
  error.value = ''
  result.value = null
  llmReview.value = null
  stopAnalysisPolling()
  stopLlmReviewPolling()
  try {
    analysisJob.value = await startGitChangeImpactJob(projectId.value, form.baselineId, {
      appId: form.appId, baseCommit: form.baseCommit, headCommit: form.headCommit,
    })
    resetPages()
    startAnalysisPolling(analysisJob.value.jobId)
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
    loading.value = false
    analysisJob.value = null
    stopAnalysisPolling()
  } finally {
  }
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '请求失败，请稍后重试'
}

// ── Git commit picker helpers ────────────────────────────────────────────────

function normalizeBranchName(branch: string) {
  return branch.replace(/^refs\/heads\//, '').replace(/^origin\//, '').trim()
}

function commitShort(value?: string) {
  return value ? value.slice(0, 8) : ''
}

function formatCommitOption(commit: GitCommitOption) {
  const short = commit.shortCommitId || commit.commitId.slice(0, 10)
  return [short, commit.message, commit.author, commit.commitTimeText].filter(Boolean).join(' · ')
}

function matchesCommitFilter(commit: GitCommitOption, keyword: string) {
  if (!keyword) return true
  const k = keyword.toLowerCase()
  return [commit.commitId, commit.shortCommitId, commit.message, commit.author]
    .filter(Boolean)
    .some(field => String(field).toLowerCase().includes(k))
}

const filteredBaseCommits = computed(() => commitOptions.value.filter(c => matchesCommitFilter(c, baseFilter.value)))
const filteredHeadCommits = computed(() => commitOptions.value.filter(c => matchesCommitFilter(c, headFilter.value)))

function togglePicker(target: 'base' | 'head', force?: boolean) {
  const next = force === undefined ? (picker.value === target ? '' : target) : (force ? target : '')
  picker.value = next
  if (next === target) {
    nextTick(() => positionPopover(target))
  }
}

function filterPicker(target: 'base' | 'head') {
  if (picker.value === target) return
  if (!commitOptions.value.length) return
  picker.value = target
  nextTick(() => positionPopover(target))
}

function positionPopover(target: 'base' | 'head') {
  const inputEl = target === 'base' ? baseInput.value : headInput.value
  if (!inputEl) return
  const rect = inputEl.getBoundingClientRect()
  const GAP = 8
  const VIEWPORT_PADDING = 12
  const viewportH = window.innerHeight
  const below = viewportH - rect.bottom - GAP - VIEWPORT_PADDING
  const above = rect.top - GAP - VIEWPORT_PADDING
  // 优先下方；空间不足时自动翻转到上方（input 之下），避免被截断
  const placeBelow = below >= 200 || below >= above
  const available = Math.max(220, placeBelow ? below : above)
  const maxHeight = Math.max(220, Math.min(720, available))
  const top = placeBelow ? rect.bottom + GAP : rect.top - GAP
  const style: Record<string, string> = {
    top: `${Math.max(VIEWPORT_PADDING, top)}px`,
    left: `${rect.left}px`,
    width: `${rect.width}px`,
    '--commit-popover-max-h': `${maxHeight}px`,
    transform: placeBelow ? 'none' : 'translateY(-100%)',
    'transform-origin': placeBelow ? 'top left' : 'bottom left',
  }
  popoverStyle[target] = style
}

function onWindowMaybeReposition() {
  if (picker.value === 'base') positionPopover('base')
  if (picker.value === 'head') positionPopover('head')
}

function onDocumentClick(event: MouseEvent) {
  if (!picker.value) return
  const target = event.target as HTMLElement | null
  if (!target) return
  if (target.closest('.commit-popover')) return
  if (target.closest('.commit-input-wrap')) return
  picker.value = ''
}

function pickCommit(target: 'base' | 'head', commit: GitCommitOption) {
  if (!commit?.commitId) return
  if (target === 'base') form.baseCommit = commit.commitId
  else form.headCommit = commit.commitId
  picker.value = ''
}

function useLatest(target: 'base' | 'head') {
  if (!latestCommit.value) return
  if (target === 'base') form.baseCommit = latestCommit.value
  else form.headCommit = latestCommit.value
}

function looksLikeCommit(value?: string) {
  const cleaned = (value || '').trim()
  return cleaned.length >= 7 && cleaned.length <= 64 && /^[0-9a-f]+$/i.test(cleaned)
}

/** 推断 git 仓库 web 地址，支持 GitHub / GitLab / Gitee / 自建仓库（带 web UI）。 */
function repoWebUrl(repoAddress?: string) {
  const raw = (repoAddress || '').trim()
  if (!raw) return ''
  let url = raw
  if (url.startsWith('git@')) {
    url = url.replace(/^git@([^:]+):/, 'https://$1/').replace(/\.git$/, '')
    return url.replace(/\/$/, '')
  }
  if (url.startsWith('ssh://git@')) {
    url = url.replace(/^ssh:\/\/git@/, 'https://').replace(/\.git$/, '')
    return url.replace(/\/$/, '')
  }
  if (/^https?:\/\//.test(url)) {
    return url.replace(/\.git$/, '').replace(/\/$/, '')
  }
  return ''
}

function repoUrlFor(commit?: string) {
  const base = repoBaseUrl.value
  if (!base || !looksLikeCommit(commit)) return ''
  return `${base}/commit/${(commit || '').trim()}`
}

function openExternal(url: string) {
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}

function openRepoRoot() {
  openExternal(repoBaseUrl.value)
}

function openRepoAtCommit(target: 'base' | 'head') {
  const value = target === 'base' ? form.baseCommit : form.headCommit
  openExternal(repoUrlFor(value))
}

function resetCommitState() {
  branchOptions.value = []
  commitOptions.value = []
  latestCommit.value = ''
  picker.value = ''
}

async function loadRepoConfig(appId: string) {
  if (!appId) {
    repoConfig.value = null
    return
  }
  try {
    repoConfig.value = await fetchRepositoryConfig(projectId.value, appId)
  } catch {
    repoConfig.value = null
  }
}

async function loadBranches(appId: string) {
  if (!appId) {
    branchOptions.value = []
    return
  }
  branchLoading.value = true
  try {
    const list = await fetchRepositoryBranches(projectId.value, appId)
    branchOptions.value = list.map(normalizeBranchName).filter(Boolean)
  } catch (err) {
    branchOptions.value = []
    error.value = messageOf(err)
  } finally {
    branchLoading.value = false
  }
}

// Constant used as the page size of the commit picker. Bump to taste.
const COMMIT_PICKER_PAGE = 50

async function loadCommits(appId: string, branch: string) {
  if (!appId || !branch) {
    commitOptions.value = []
    commitsLimit.value = COMMIT_PICKER_PAGE
    commitsReachedTail.value = true
    return
  }
  commitsLoading.value = true
  commitsLimit.value = COMMIT_PICKER_PAGE
  commitsReachedTail.value = false
  try {
    commitOptions.value = await fetchGitRecentCommits(projectId.value, appId, branch, COMMIT_PICKER_PAGE)
  } catch (err) {
    commitOptions.value = []
    error.value = messageOf(err)
  } finally {
    commitsLoading.value = false
  }
}

async function loadMoreCommits() {
  const appId = form.appId
  const branch = form.branch
  if (!appId || !branch) return
  if (commitsLoading.value || moreLoading.value || commitsReachedTail.value) return
  if (commitOptions.value.length < commitsLimit.value) {
    // Already loaded fewer than the current page → no more chunks to request
    commitsReachedTail.value = true
    return
  }
  moreLoading.value = true
  const nextLimit = commitsLimit.value + COMMIT_PICKER_PAGE
  try {
    const more = await fetchGitRecentCommits(projectId.value, appId, branch, nextLimit)
    const appended = more.slice(commitOptions.value.length)
    if (appended.length) commitOptions.value = [...commitOptions.value, ...appended]
    commitsLimit.value = nextLimit
    if (appended.length < COMMIT_PICKER_PAGE || more.length < nextLimit) {
      commitsReachedTail.value = true
    }
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    moreLoading.value = false
  }
}

async function fetchLatestCommit() {
  if (!form.appId || !form.branch) return
  latestCommitLoading.value = true
  try {
    latestCommit.value = (await fetchGitLatestCommit(projectId.value, form.appId, form.branch)) || ''
  } catch (err) {
    latestCommit.value = ''
    error.value = messageOf(err)
  } finally {
    latestCommitLoading.value = false
  }
}

async function onAppChange() {
  form.branch = ''
  form.baseCommit = ''
  form.headCommit = ''
  resetCommitState()
  if (!form.appId) {
    repoConfig.value = null
    return
  }
  await loadRepoConfig(form.appId)
  await loadBranches(form.appId)
}

async function onBranchChange() {
  form.baseCommit = ''
  form.headCommit = ''
  picker.value = ''
  commitOptions.value = []
  latestCommit.value = ''
  if (!form.appId || !form.branch) return
  await Promise.all([loadCommits(form.appId, form.branch), fetchLatestCommit()])
}

async function refreshCommits() {
  if (!form.appId || !form.branch) return
  await Promise.all([loadCommits(form.appId, form.branch), fetchLatestCommit()])
}

function rebuildLazyObservers() {
  baseLazyObserver?.disconnect()
  headLazyObserver?.disconnect()
  baseLazyObserver = null
  headLazyObserver = null
  if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return
  const cb: IntersectionObserverCallback = entries => {
    if (!entries.some(entry => entry.isIntersecting)) return
    if (commitsLoading.value || moreLoading.value || commitsReachedTail.value) return
    if (!form.appId || !form.branch) return
    void loadMoreCommits()
  }
  baseLazyObserver = new IntersectionObserver(cb, { rootMargin: '320px 0px', threshold: 0 })
  headLazyObserver = new IntersectionObserver(cb, { rootMargin: '320px 0px', threshold: 0 })
  if (baseSentinel.value) baseLazyObserver.observe(baseSentinel.value)
  if (headSentinel.value) headLazyObserver.observe(headSentinel.value)
}

watch(
  () => [
    commitOptions.value.length,
    commitsReachedTail.value,
    picker.value,
    baseSentinel.value,
    headSentinel.value,
  ],
  () => rebuildLazyObservers(),
  { flush: 'post' }
)

watch(() => [resultFilters.keyword, resultFilters.classification], resetPages)

async function pollAnalysisJob(jobId: string) {
  try {
    const job = await fetchGitChangeImpactJob(projectId.value, jobId)
    if (analysisJob.value?.jobId && analysisJob.value.jobId !== jobId) return
    analysisJob.value = job
    if (job.status === 'COMPLETED' && job.result) {
      result.value = job.result
      resetPages()
      loading.value = false
      stopAnalysisPolling()
      startLlmReviewPolling(job.result.report.id)
      toast.success('Git 变更影响分析完成')
    } else if (job.status === 'FAILED') {
      error.value = job.error || job.message || 'Git 影响分析失败'
      loading.value = false
      stopAnalysisPolling()
      toast.error(error.value)
    }
  } catch (err) {
    error.value = messageOf(err)
    loading.value = false
    stopAnalysisPolling()
    toast.error(error.value)
  }
}

function startAnalysisPolling(jobId: string) {
  stopAnalysisPolling()
  void pollAnalysisJob(jobId)
  analysisPollTimer = window.setInterval(() => void pollAnalysisJob(jobId), 1000)
}

function stopAnalysisPolling() {
  if (analysisPollTimer) window.clearInterval(analysisPollTimer)
  analysisPollTimer = undefined
}

async function pollLlmReview(reportId: string) {
  try {
    const progress = await fetchGitImpactLlmReview(projectId.value, reportId)
    if (result.value?.report.id !== reportId) return
    llmReview.value = progress
    result.value.report.llmJudgements = progress.judgements || []
    if (['COMPLETED', 'FAILED', 'UNAVAILABLE', 'NOT_FOUND'].includes(progress.status)) stopLlmReviewPolling()
  } catch (err) {
    stopLlmReviewPolling()
  }
}

function startLlmReviewPolling(reportId: string) {
  stopLlmReviewPolling()
  void pollLlmReview(reportId)
  llmPollTimer = window.setInterval(() => void pollLlmReview(reportId), 2500)
}

function stopLlmReviewPolling() {
  if (llmPollTimer) window.clearInterval(llmPollTimer)
  llmPollTimer = undefined
}

function resetPages() {
  pages.files = 1
  pages.direct = 1
  pages.candidates = 1
  pages.criteria = 1
  pages.testcases = 1
}

function activeSymbol(change: GitImpactReport['directChanges'][number]) {
  return change.newSymbol || change.oldSymbol
}

function changeKey(change: GitImpactReport['directChanges'][number]) {
  return change.symbolKey || change.newKey || change.oldKey || activeSymbol(change)?.key || ''
}

function badgeClass(value: string) {
  const normalized = value.toLowerCase()
  if (normalized.includes('delete') || normalized.includes('reject')) return 'danger'
  if (normalized.includes('add') || normalized.includes('confirm')) return 'success'
  if (normalized.includes('possible') || normalized.includes('unknown')) return 'warning'
  return 'neutral'
}

function candidateKey(candidate: GitImpactReport['candidates'][number]) {
  return `${candidate.seedSymbol}->${candidate.targetSymbol}:${candidate.direction}:${candidate.distance}`
}

function classificationClass(value: string) {
  if (value === 'TRANSITIVE') return 'success'
  if (value === 'POSSIBLE' || value === 'UNKNOWN') return 'warning'
  return 'neutral'
}

function classificationText(value: string) {
  return ({ DIRECT: '直接变更', TRANSITIVE: '确定传播', POSSIBLE: '可能影响', UNKNOWN: '待确认' } as Record<string, string>)[value] || value
}

function directionText(value: string) {
  return ({ UPSTREAM: '上游调用方', DOWNSTREAM: '下游依赖方', TRACEABILITY: '追溯映射' } as Record<string, string>)[value] || value
}

function facetText(value: string) {
  return ({
    BODY: '方法体',
    CONTROL_FLOW: '控制流',
    CALL: '调用关系',
    FIELD: '字段',
    ANNOTATION: '注解',
    VISIBILITY: '可见性',
    RETURN_TYPE: '返回值',
    PARAMETER: '参数',
    EXCEPTION: '异常',
    CONSTANT: '常量',
    SQL: 'SQL',
    CONFIG: '配置',
  } as Record<string, string>)[value] || value
}

function filePath(file: GitImpactReport['changeSet']['files'][number]) {
  if (file.changeType === 'RENAME' && file.oldPath && file.newPath) return `${file.oldPath} → ${file.newPath}`
  return file.newPath && file.newPath !== '/dev/null' ? file.newPath : file.oldPath || '未知文件'
}

function formatRanges(ranges?: Array<{ startLine: number; endLine: number }>) {
  if (!ranges?.length) return ''
  return ranges.map(rangeText).filter(Boolean).join(', ')
}

function llmJudgement(candidate: GitImpactReport['candidates'][number]) {
  const id = `${candidate.seedSymbol}->${candidate.targetSymbol}`
  return result.value?.report.llmJudgements.find(item => item.candidateId === id)
}

function llmText(value?: string) {
  return ({ CONFIRM: '确认', REJECT: '否决', UNCERTAIN: '不确定' } as Record<string, string>)[value || ''] || '未判定'
}

function matchesKeyword(values: Array<string | undefined | null>) {
  if (!keyword.value) return true
  return values.some(value => value?.toLowerCase().includes(keyword.value))
}

function paginate<T>(items: T[], page: number, pageSize: number) {
  const start = (Math.max(1, page) - 1) * Math.max(1, pageSize)
  return items.slice(start, start + Math.max(1, pageSize))
}

function percent(value: number) {
  return `${Math.round((value || 0) * 100)}%`
}

function rangeText(range?: { startLine: number; endLine: number }) {
  if (!range) return ''
  return range.startLine === range.endLine ? String(range.startLine) : `${range.startLine}-${range.endLine}`
}

function reasonText(value?: string) {
  return ({
    'Tree-sitter call-site candidate': '静态调用点候选',
    'golden-call': '静态调用关系',
  } as Record<string, string>)[value || ''] || value || '规则传播'
}

function snippetLines(change: GitImpactReport['directChanges'][number]) {
  const snippet = activeSymbol(change)?.snippet || ''
  const startLine = snippetStartLine(change)
  return snippet.split(/\r?\n/).map((text, index) => ({
    key: `${startLine + index}:${index}:${text}`,
    number: startLine + index,
    text,
  }))
}

function snippetStartLine(change: GitImpactReport['directChanges'][number]) {
  return change.evidenceRanges?.[0]?.startLine || activeSymbol(change)?.range?.startLine || 1
}

function shortCommit(value?: string) {
  return value ? value.slice(0, 8) : 'unknown'
}

function stageText(value: string) {
  return ({
    PENDING: '等待执行',
    STARTING: '启动任务',
    PREPARING: '准备分析',
    FETCHING_DIFF: '读取 Git Diff',
    READING_CONTENT: '读取文件内容',
    ANALYZING_FILES: '解析变更文件',
    PROPAGATING: '传播影响范围',
    SCHEDULING_LLM: '创建 LLM 任务',
    MAPPING_TRACEABILITY: '映射追溯关系',
    COMPLETED: '分析完成',
    FAILED: '分析失败',
  } as Record<string, string>)[value] || value
}

function symbolMeta(change: GitImpactReport['directChanges'][number]) {
  const symbol = activeSymbol(change)
  const parts = [symbol?.kind, symbol?.signature].filter(Boolean)
  return parts.length ? parts.join(' · ') : changeKey(change)
}

function symbolName(symbolKey?: string | null) {
  if (!symbolKey) return '未知符号'
  const cleaned = symbolKey.replace(/^java:\/\//, '')
  const memberIndex = cleaned.indexOf('#')
  if (memberIndex >= 0) {
    const typeName = cleaned.slice(0, memberIndex).split('.').pop()
    return `${typeName}.${cleaned.slice(memberIndex + 1)}`
  }
  return cleaned.split('.').pop() || cleaned
}

function typeText(value: string) {
  return ({ ADD: '新增', MODIFY: '修改', DELETE: '删除', RENAME: '重命名', COPY: '复制', MOVE: '移动', SIGNATURE_CHANGE: '签名变更' } as Record<string, string>)[value] || value
}

onMounted(() => {
  loadOverview()
  window.addEventListener('resize', onWindowMaybeReposition)
  window.addEventListener('scroll', onWindowMaybeReposition, true)
  document.addEventListener('mousedown', onDocumentClick)
})
onBeforeUnmount(() => {
  stopAnalysisPolling()
  stopLlmReviewPolling()
  window.removeEventListener('resize', onWindowMaybeReposition)
  window.removeEventListener('scroll', onWindowMaybeReposition, true)
  document.removeEventListener('mousedown', onDocumentClick)
  baseLazyObserver?.disconnect()
  headLazyObserver?.disconnect()
})
</script>

<style scoped>
.git-impact-page { display: grid; gap: 18px; }
.page-header,
.header-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions {
  justify-content: flex-end;
}
@keyframes refresh-spin {
  to { transform: rotate(360deg); }
}
.form-stack { display: grid; gap: 14px; }
.analyze-action-row {
  display: flex;
  justify-content: flex-end;
}
.analyze-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-height: 48px;
  width: fit-content;
  min-width: 220px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-primary-hover));
  color: #fff;
  box-shadow: 0 10px 22px rgba(var(--oat-primary-rgb), .22);
  font-weight: 800;
  line-height: 1;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, opacity .16s ease;
}
.analyze-button:hover:not(:disabled) {
  box-shadow: 0 14px 28px rgba(var(--oat-primary-rgb), .30);
  transform: translateY(-2px);
}
.analyze-button:active:not(:disabled) {
  box-shadow: 0 5px 12px rgba(var(--oat-primary-rgb), .18);
  transform: translateY(0) scale(.98);
}
.analyze-button:focus-visible {
  outline: none;
  box-shadow: var(--oat-focus-ring), 0 10px 22px rgba(var(--oat-primary-rgb), .22);
}
.analyze-button:disabled {
  border-color: var(--oat-border);
  background: var(--oat-surface-soft);
  color: var(--oat-text-muted);
  box-shadow: none;
  opacity: 1;
}
.analyze-icon {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}
.analyze-icon.spinning {
  animation: refresh-spin .8s linear infinite;
}
.analyze-hint {
  margin-left: 3px;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
}
.analysis-progress { display: grid; gap: 8px; padding: 12px; border: 1px solid rgba(37, 99, 235, .18); border-radius: 8px; background: #eff6ff; }
.analysis-progress.completed { border-color: rgba(22, 163, 74, .22); background: #f0fdf4; }
.analysis-progress.failed { border-color: rgba(220, 38, 38, .2); background: #fef2f2; }
.progress-top,
.progress-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.progress-top strong { color: var(--oat-text); overflow-wrap: anywhere; }
.progress-top span { color: #2563eb; font-weight: 900; font-variant-numeric: tabular-nums; }
.analysis-progress.completed .progress-top span { color: #16a34a; }
.analysis-progress.failed .progress-top span { color: #dc2626; }
.progress-track { overflow: hidden; height: 8px; border-radius: 999px; background: rgba(37, 99, 235, .13); }
.progress-track div { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #2563eb, #0f766e); transition: width .25s ease; }
.progress-meta { color: var(--oat-text-muted); font-size: 12px; font-weight: 700; }
.inline-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.form-stack label { display: grid; gap: 7px; color: #475569; font-size: 13px; font-weight: 700; }
.form-stack input, .form-stack select { min-height: 42px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 10px; padding: 9px 11px; background: #fff; color: #1e293b; font: inherit; }

.branch-cell .branch-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}
.branch-cell select { min-height: 42px; }
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  border: 1px solid rgba(15, 23, 42, .13);
  background: #fff;
  color: #475569;
  cursor: pointer;
  transition: border-color .15s ease, color .15s ease, background .15s ease;
}
.icon-button:hover:not(:disabled) { color: #0f766e; border-color: rgba(15, 118, 110, .35); }
.icon-button:focus-visible { outline: none; box-shadow: var(--oat-focus-ring); }
.icon-button:disabled { opacity: .45; cursor: not-allowed; background: var(--oat-surface-soft); }
.icon-button svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}
.icon-button.repo-link svg { stroke-width: 1.8; }
.icon-button .spinning { animation: refresh-spin .8s linear infinite; }

.latest-hint {
  display: grid;
  gap: 6px;
  align-content: center;
  padding: 9px 12px;
  border: 1px solid rgba(15, 118, 110, .14);
  border-radius: 10px;
  background: #f0fdfa;
  color: #475569;
  font-size: 13px;
  font-weight: 700;
}
.latest-hint code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #0f766e;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 14px;
  font-weight: 800;
}
.latest-hint .muted { color: #64748b; font-weight: 600; }

.commits-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.commit-field {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 12px;
  background: #fff;
}
.commit-field.expanded { border-color: rgba(15, 118, 110, .35); box-shadow: 0 0 0 3px rgba(15, 118, 110, .08); }
.commit-field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.commit-label { color: #475569; font-size: 13px; font-weight: 800; }
.commit-field-actions { display: inline-flex; gap: 6px; flex-wrap: wrap; }
.ghost-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 30px;
  padding: 4px 10px;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 999px;
  background: #fff;
  color: #475569;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color .15s ease, color .15s ease, background .15s ease;
}
.ghost-button:hover:not(:disabled) { color: #0f766e; border-color: rgba(15, 118, 110, .35); }
.ghost-button:focus-visible { outline: none; box-shadow: var(--oat-focus-ring); }
.ghost-button:disabled { opacity: .45; cursor: not-allowed; background: var(--oat-surface-soft); }

.commit-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.commit-input {
  width: 100%;
  min-height: 42px;
  border: 1px solid rgba(15, 23, 42, .13);
  border-radius: 10px;
  padding: 9px 11px;
  background: #fff;
  color: #1e293b;
  font: inherit;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 14px;
}
.commit-input:focus-visible { outline: none; border-color: rgba(15, 118, 110, .5); box-shadow: var(--oat-focus-ring); }
.commit-preview {
  position: absolute;
  right: 44px;
  top: 50%;
  transform: translateY(-50%);
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  pointer-events: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}
.commit-input-wrap .commit-popover-trigger {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 8px;
  background: #f8fafc;
  color: #475569;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background .15s ease, color .15s ease, border-color .15s ease;
}
.commit-input-wrap .commit-popover-trigger:hover:not(:disabled) {
  background: #ccfbf1;
  color: #0f766e;
  border-color: rgba(15, 118, 110, .35);
}
.commit-input-wrap .commit-popover-trigger:focus-visible {
  outline: none;
  box-shadow: var(--oat-focus-ring);
}
.commit-input-wrap .commit-popover-trigger:disabled { opacity: .4; cursor: not-allowed; background: var(--oat-surface-soft); }
.commit-input-wrap .commit-popover-trigger svg { width: 16px; height: 16px; transition: transform .15s ease; }
.commit-input-wrap .commit-popover-trigger svg.open { transform: rotate(180deg); }
.commit-input-wrap .commit-input { padding-right: 44px; }

.commit-popover {
  position: fixed;
  z-index: 1100;
  display: grid;
  grid-template-rows: auto 1fr auto;
  max-height: var(--commit-popover-max-h, min(720px, 80vh));
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  box-shadow: 0 16px 32px rgba(15, 23, 42, .14), 0 2px 6px rgba(15, 23, 42, .06);
  overflow: hidden;
}
.commit-popover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(15, 23, 42, .08);
  background: #ffffff;
}
.commit-popover-title {
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .2px;
  white-space: nowrap;
}
.commit-popover-filter {
  flex: 1;
  min-width: 0;
  height: 30px;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 8px;
  padding: 4px 10px;
  font: inherit;
  font-size: 12px;
  background: #fff;
  color: #1e293b;
}
.commit-popover-filter:focus-visible {
  outline: none;
  border-color: rgba(15, 118, 110, .5);
  box-shadow: var(--oat-focus-ring);
}
.commit-popover-body {
  display: grid;
  align-content: start;
  gap: 6px;
  padding: 10px;
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 118, 110, .35) transparent;
}
.commit-popover-body::-webkit-scrollbar { width: 8px; height: 8px; }
.commit-popover-body::-webkit-scrollbar-thumb {
  background: rgba(15, 118, 110, .28);
  border-radius: 4px;
}
.commit-popover-body::-webkit-scrollbar-thumb:hover { background: rgba(15, 118, 110, .5); }
.commit-popover-body::-webkit-scrollbar-track { background: transparent; }
.commit-popover-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-top: 1px solid rgba(15, 23, 42, .08);
  background: #ffffff;
}
.commit-popover-foot .commit-popover-summary {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .2px;
  white-space: nowrap;
}
.commit-popover-foot .commit-popover-summary strong {
  color: #0f766e;
  font-weight: 800;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
}
.commit-popover-load-more {
  border: 1px solid rgba(15, 118, 110, .35);
  border-radius: 8px;
  padding: 5px 12px;
  background: #f0fdfa;
  color: #0f766e;
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: background .15s ease, border-color .15s ease;
}
.commit-popover-load-more:hover { background: #ccfbf1; border-color: rgba(15, 118, 110, .55); }
.commit-popover-load-more:disabled { opacity: .55; cursor: not-allowed; background: #f0fdfa; }
.commit-popover-sentinel {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 12px;
  margin-top: 4px;
  border-top: 1px dashed rgba(15, 23, 42, .12);
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}
.commit-popover-sentinel .dot-loader {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgba(15, 118, 110, .25);
  border-top-color: rgba(15, 118, 110, .85);
  animation: commit-picker-spin 0.8s linear infinite;
}
.commit-popover-sentinel.commit-popover-tail {
  border-top-style: solid;
  border-top-color: rgba(15, 23, 42, .08);
  color: #0f766e;
}
.commit-popover-sentinel.commit-popover-tail .dot-loader { display: none; }
@keyframes commit-picker-spin {
  to { transform: rotate(360deg); }
}
.commit-picker-row {
  display: grid;
  gap: 4px;
  padding: 9px 12px;
  border: 1px solid rgba(15, 23, 42, .06);
  border-radius: 10px;
  background: #fff;
  color: #1e293b;
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: border-color .15s ease, background .15s ease, box-shadow .15s ease, transform .15s ease;
}
.commit-picker-row:hover {
  border-color: rgba(15, 118, 110, .35);
  background: #f0fdfa;
  box-shadow: 0 1px 2px rgba(15, 118, 110, .08);
}
.commit-picker-row.active {
  border-color: rgba(15, 118, 110, .55);
  background: #ecfeff;
  box-shadow: 0 0 0 2px rgba(15, 118, 110, .12) inset;
}
.commit-picker-row:focus-visible {
  outline: none;
  border-color: rgba(15, 118, 110, .5);
  box-shadow: var(--oat-focus-ring);
}
.commit-picker-row .commit-line-main {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-width: 0;
}
.commit-picker-row .commit-sha {
  color: #0f766e;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  font-weight: 800;
  background: rgba(15, 118, 110, .08);
  padding: 2px 6px;
  border-radius: 4px;
  text-align: center;
  letter-spacing: -.3px;
}
.commit-picker-row .commit-message {
  min-width: 0;
  color: #1e293b;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.commit-picker-row .commit-time {
  color: #475569;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 11px;
  font-weight: 600;
  background: #e2e8f0;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
  letter-spacing: .2px;
}
.commit-picker-row .commit-line-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 88px;
  min-width: 0;
}
.commit-picker-row .commit-author {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  white-space: nowrap;
}
.commit-picker-row .commit-author svg { color: #94a3b8; flex-shrink: 0; }
.commit-picker-row .commit-full {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 11px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.result-panel { display: grid; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.summary-item { display: grid; gap: 2px; min-height: 72px; align-content: center; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
.summary-item strong { font-size: 24px; line-height: 1; color: var(--oat-text); }
.summary-item span { color: var(--oat-text-muted); font-size: 12px; font-weight: 700; }
.summary-help { width: fit-content; cursor: help; text-decoration: underline dotted; text-underline-offset: 3px; }
.commit-strip { display: grid; grid-template-columns: 1fr 1fr auto; gap: 10px; padding: 12px; border: 1px solid rgba(15, 118, 110, .14); border-radius: 8px; background: #f0fdfa; }
.commit-strip div { display: grid; gap: 4px; min-width: 0; }
.commit-strip span { color: #0f766e; font-size: 12px; font-weight: 800; }
.commit-strip code { overflow: hidden; color: #134e4a; text-overflow: ellipsis; white-space: nowrap; }
.llm-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; color: var(--oat-text-muted); font-size: 13px; }
.llm-status strong { color: var(--oat-text); }
.llm-status.running,
.llm-status.pending { border-color: rgba(37, 99, 235, .2); background: #eff6ff; }
.llm-status.completed { border-color: rgba(22, 163, 74, .22); background: #f0fdf4; }
.llm-status.failed,
.llm-status.unavailable,
.llm-status.not_found { border-color: rgba(217, 119, 6, .24); background: #fffbeb; }
.result-toolbar { display: grid; grid-template-columns: minmax(280px, 1fr) 180px; gap: 12px; }
.result-toolbar label { display: grid; gap: 6px; color: #475569; font-size: 12px; font-weight: 800; }
.result-toolbar input,
.result-toolbar select { min-height: 38px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 8px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; }
.result-layout {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.result-block {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}
.files-block,
.trace-block { grid-column: span 12; }
.direct-block,
.candidates-block { grid-column: span 6; }
.block-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 9px; border-bottom: 1px solid rgba(15, 23, 42, .08); }
.block-head h3,
.trace-columns h4 { margin: 0; color: var(--oat-text); font-size: 15px; }
.block-head span { color: var(--oat-text-muted); font-size: 12px; }
.file-row,
.change-card,
.candidate-card,
.trace-card { display: grid; gap: 8px; min-width: 0; overflow: hidden; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
.result-block > .file-row,
.result-block > .change-card,
.result-block > .candidate-card,
.result-block .trace-card { background: #f8fafc; }
.file-row {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
}
.file-row .row-main { align-items: center; }
.file-row .meta-line {
  grid-column: 1 / -1;
  padding-left: 62px;
}
.row-main,
.change-title,
.candidate-top { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.row-main strong,
.change-title strong,
.candidate-top strong,
.trace-card strong { min-width: 0; overflow-wrap: anywhere; color: #0f766e; }
.change-title div { display: grid; gap: 2px; min-width: 0; }
.change-title div span,
.trace-card span { color: var(--oat-text-muted); font-size: 13px; overflow-wrap: anywhere; }
.badge { flex: 0 0 auto; min-width: 52px; border-radius: 999px; padding: 3px 8px; text-align: center; font-size: 12px; font-weight: 800; }
.badge.neutral { background: #eef2ff; color: #3730a3; }
.badge.success { background: #dcfce7; color: #166534; }
.badge.warning { background: #fef3c7; color: #92400e; }
.badge.danger { background: #fee2e2; color: #991b1b; }
.meta-line { display: flex; flex-wrap: wrap; gap: 7px 12px; color: var(--oat-text-muted); font-size: 12px; }
.meta-line span { overflow-wrap: anywhere; }
.facet-list { display: flex; flex-wrap: wrap; gap: 6px; }
.facet-list span { border-radius: 6px; padding: 3px 7px; background: var(--oat-surface-soft); color: #334155; font-size: 12px; font-weight: 700; }
.snippet-box { min-width: 0; overflow: hidden; border-top: 1px solid rgba(15, 23, 42, .08); padding-top: 8px; }
.snippet-box summary { color: #0f766e; cursor: pointer; font-size: 13px; font-weight: 800; }
.snippet-code {
  width: 100%;
  max-width: 100%;
  max-height: 260px;
  overflow: auto;
  box-sizing: border-box;
  margin: 8px 0 0;
  border-radius: 8px;
  padding: 10px 0;
  background: #0f172a;
  color: #e2e8f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.55;
}
.snippet-line {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 12px;
  min-width: max-content;
  padding: 0 12px 0 0;
}
.snippet-line-number {
  position: sticky;
  left: 0;
  padding: 0 10px;
  background: #0f172a;
  color: #64748b;
  text-align: right;
  user-select: none;
}
.snippet-line code {
  display: block;
  white-space: pre;
}
.confidence { margin-left: auto; color: #0f766e; font-weight: 800; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 9px; border-radius: 8px; background: #f8fafc; color: #334155; font-size: 12px; }
.path-chain span { overflow-wrap: anywhere; }
.path-chain b { color: #64748b; font-size: 11px; }
.llm-note { margin: 0; color: var(--oat-text-muted); font-size: 13px; }
.symbol-hit-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 10px;
  border: 1px solid rgba(15, 118, 110, .12);
  border-radius: 8px;
  background: #ecfeff;
}
.symbol-hit-strip span,
.symbol-hit-strip b {
  max-width: 100%;
  overflow: hidden;
  border: 1px solid rgba(15, 118, 110, .16);
  border-radius: 999px;
  padding: 3px 8px;
  background: #fff;
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-warning {
  padding: 10px 12px;
  border: 1px solid rgba(217, 119, 6, .18);
  border-radius: 8px;
  background: #fffbeb;
  color: #92400e;
}
.trace-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.trace-columns > div {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
  padding-top: 2px;
}
.trace-item-detail {
  align-content: start;
}
.trace-item-detail summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  cursor: pointer;
  list-style: none;
}
.trace-item-detail summary::-webkit-details-marker {
  display: none;
}
.trace-item-detail summary > span {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.trace-item-detail summary em {
  color: var(--oat-text-muted);
  font-size: 13px;
  font-style: normal;
  overflow-wrap: anywhere;
}
.trace-item-detail summary b {
  border-radius: 999px;
  padding: 2px 8px;
  background: #fff;
  color: #0f766e;
  font-size: 12px;
  font-weight: 900;
}
.trace-item-detail[open] summary {
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(15, 23, 42, .08);
}
.trace-inline-detail {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding-top: 2px;
}
.trace-inline-detail p,
.trace-inline-detail dd {
  margin: 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.trace-inline-detail dl {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
}
.trace-inline-detail dt {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.detail-meta span {
  border-radius: 999px;
  padding: 2px 7px;
  background: #fff;
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}
.empty-inline { padding: 10px 0; color: #64748b; font-size: 13px; }
@media (max-width: 1120px) {
  .direct-block,
  .candidates-block { grid-column: span 12; }
}
@media (max-width: 760px) {
  .inline-grid { grid-template-columns: 1fr; }
  .commits-grid { grid-template-columns: 1fr; }
  .branch-cell .branch-control { grid-template-columns: minmax(0, 1fr) auto auto; }
  .commit-picker-row .commit-line-main { grid-template-columns: 70px minmax(0, 1fr); }
  .commit-picker-row .commit-time { grid-column: 1 / -1; justify-self: start; padding-left: 80px; }
  .commit-picker-row .commit-line-meta { padding-left: 80px; flex-wrap: wrap; }
  .commit-picker-row .commit-full { flex-basis: 100%; padding-left: 0; }
  .summary-grid,
  .commit-strip,
  .result-toolbar,
  .result-layout,
  .trace-columns { grid-template-columns: 1fr; }
  .files-block,
  .trace-block,
  .direct-block,
  .candidates-block { grid-column: auto; }
  .analyze-action-row { justify-content: stretch; }
  .analyze-button { width: 100%; }
  .file-row .meta-line { padding-left: 0; }
  .trace-inline-detail dl { grid-template-columns: 1fr; }
  .candidate-top { display: grid; }
  .confidence { margin-left: 0; }
}
</style>
