<template>
  <section
    class="verification-page"
    @mouseover="showHelp"
    @mouseout="hideHelpOnLeave"
    @focusin="showHelp"
    @focusout="hideHelp"
  >
    <header class="page-header plain-header verification-header">
      <div>
        <div class="eyebrow">AI Requirement Verification</div>
        <h1>AI 需求一致性验证</h1>
        <p class="subtext">围绕需求、测试用例、源码和依据建立可追溯的 AI 分析闭环。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadOverview" />
        <button type="button" class="primary-button" @click="activeWorkspace = 'library'">导入资料</button>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <nav class="workspace-tabs" aria-label="AI 验证工作区">
      <button
        v-for="item in workspaceTabs"
        :key="item.key"
        type="button"
        :class="{ active: activeWorkspace === item.key }"
        @click="activeWorkspace = item.key"
      >
        <strong>{{ item.label }}</strong>
        <span>{{ item.description }}</span>
      </button>
    </nav>

    <section v-if="activeWorkspace === 'library'" class="workspace-section two-column">
      <section class="panel-section">
        <div class="section-head">
          <h2>导入分析资料</h2>
          <span>新增资料版本</span>
        </div>
        <p class="section-tip">
          这里只负责新增资料。导入成功后会进入右侧资料库，创建分析基线时再选择具体版本。
        </p>

        <div class="asset-import-grid">
          <article
            v-for="asset in assetInputs"
            :key="asset.type"
            class="asset-import"
            :class="{ collapsed: !isImportExpanded(asset.type), dragging: draggingAssetType === asset.type }"
            @dragenter.prevent="onAssetDragEnter(asset.type, $event)"
            @dragover.prevent="onAssetDragOver(asset.type, $event)"
            @dragleave="onAssetDragLeave($event)"
            @drop.prevent="onAssetDrop(asset.type, $event)"
          >
            <button type="button" class="asset-toggle" @click="toggleImport(asset.type)">
              <span>
                <strong>{{ asset.label }}</strong>
                <small>{{ asset.hint }} · 已导入 {{ importedCountByType(asset.type) }} 条</small>
              </span>
              <em>{{ isImportExpanded(asset.type) ? '收起' : '展开' }}</em>
            </button>
            <div v-if="isImportExpanded(asset.type)" class="asset-import-body">
              <label class="asset-file-drop">
                <input type="file" :accept="assetAccept(asset.type)" @change="onFileChange(asset.type, $event)" />
                <span>{{ files[asset.type]?.name || '选择文件' }}</span>
                <small>{{ files[asset.type]?.name ? '已选择文件，可重新选择或拖放替换' : '点击选择文件，或把文件拖放到此区域' }}</small>
              </label>
              <textarea v-model="pasteInputs[asset.type]" :placeholder="asset.placeholder"></textarea>
              <input v-model.trim="sourceVersions[asset.type]" type="text" placeholder="外部版本 / Commit / 批次号" />
              <button type="button" :disabled="importing === asset.type || !hasImportInput(asset.type)" @click="importAsset(asset.type)">
                {{ importing === asset.type ? '导入中...' : `导入${asset.label}` }}
              </button>
              <div v-if="asset.type === 'SOURCE'" class="source-import-divider">
                <span>或</span>
              </div>
              <div v-if="asset.type === 'SOURCE'" class="git-source-panel">
                <div class="section-head compact-head">
                  <h3>从源码工程导入</h3>
                  <span>复用仓库配置</span>
                </div>
                <label>
                  <span>源码工程</span>
                  <select v-model="gitForm.appId">
                    <option value="">请选择已配置仓库的源码工程</option>
                    <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
                  </select>
                </label>
                <p class="field-help">
                  仓库地址、用户名和 Token 统一在“源码工程 / 仓库配置”维护，这里只拉取一版源码资料给 AI 分析。
                  <RouterLink v-if="gitForm.appId" :to="`/p/${projectId}/apps/${gitForm.appId}/repository`">去仓库配置</RouterLink>
                </p>
                <div class="inline-grid">
                  <label>
                    <span>分支</span>
                    <input v-model.trim="gitForm.branch" type="text" placeholder="留空取默认分支最新" />
                  </label>
                  <label>
                    <span>Commit</span>
                    <input v-model.trim="gitForm.commit" type="text" placeholder="留空取分支最新" />
                  </label>
                  <label>
                    <span>AI 摘要文件数</span>
                    <input v-model.number="gitForm.maxFiles" type="number" min="1" max="2000" />
                  </label>
                </div>
                <button type="button" class="secondary-button full" :disabled="gitImporting" @click="importGitSource">
                  {{ gitImporting ? '拉取中...' : '从源码工程导入源码' }}
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="panel-section imported-assets">
        <div class="section-head">
          <h2>资料库</h2>
          <span>{{ importedAssetCount }} 条</span>
        </div>
        <p class="section-tip">这里仅查看已导入资料。点击资料可把它预选到“分析基线”。</p>
        <div v-if="!importedAssetCount" class="empty-state compact">
          <strong>还没有导入资料</strong>
          <span>导入任意资料后，可在创建分析基线时选择使用。</span>
        </div>
        <div v-else class="asset-library">
          <article v-for="group in visibleAssetGroups" :key="group.key" class="asset-group">
            <div class="asset-group-head">
              <strong>{{ group.label }}</strong>
              <span>{{ group.items.length }} 条</span>
            </div>
            <article v-for="asset in group.items" :key="asset.id" class="asset-record">
              <button type="button" class="record-main" :title="asset.id" @click="selectAssetForBaseline(group.key, asset.id)">
                <strong>{{ assetDisplayName(asset) }}</strong>
                <span>{{ sourceTypeText(asset.sourceType) }} · {{ storageText(asset.storageType) }} · {{ formatBytes(asset.contentSize) }} · {{ formatTime(asset.capturedAt) }}</span>
                <small v-if="asset.contentPreview">{{ asset.contentPreview }}</small>
              </button>
              <div class="record-actions">
                <button type="button" @click="startEditAsset(asset)">编辑</button>
                <button type="button" class="danger-button" @click="deleteAsset(asset)">删除</button>
              </div>
            </article>
          </article>
        </div>
      </section>

      <section v-if="editingAsset" class="panel-section edit-panel">
        <div class="section-head">
          <h2>编辑资料</h2>
          <span>{{ assetTypeLabel(editingAsset.assetType) }}</span>
        </div>
        <div class="form-stack">
          <label>
            <span>显示名称</span>
            <input v-model.trim="assetEditForm.fileName" type="text" placeholder="资料名称" />
          </label>
          <label>
            <span>版本号 / Commit / 批次号</span>
            <input v-model.trim="assetEditForm.sourceVersion" type="text" placeholder="版本号 / Commit / 批次号" />
          </label>
          <label>
            <span>资料内容</span>
            <textarea v-model="assetEditForm.content" class="large-textarea" placeholder="资料内容"></textarea>
          </label>
          <div class="inline-actions">
            <button type="button" class="secondary-button" @click="cancelEditAsset">取消</button>
            <button type="button" class="primary-button" :disabled="assetUpdating" @click="saveAssetEdit">
              {{ assetUpdating ? '保存中...' : '保存资料' }}
            </button>
          </div>
        </div>
      </section>
    </section>

    <section v-else-if="activeWorkspace === 'baseline'" class="workspace-section two-column">
      <section class="panel-section">
        <div class="section-head">
          <h2>{{ editingBaselineId ? '编辑分析基线' : '创建分析基线' }}</h2>
          <span>锁定本次输入</span>
        </div>
        <p class="section-tip">
          基线是一组不可变分析输入。这里只负责创建和选择基线，不展示 AI 审核结果。
        </p>
        <div class="form-stack">
          <label>
            <span>基线名称</span>
            <input v-model.trim="baselineForm.name" type="text" placeholder="例如：登录模块 V2.0 发布前验证" />
          </label>
          <label>
            <span>选择需求资料版本</span>
            <select v-model="baselineForm.requirementAssetId">
              <option value="">不选择需求资料</option>
              <option v-for="asset in overview.requirements" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
            </select>
          </label>
          <label>
            <span>选择测试用例资料版本</span>
            <select v-model="baselineForm.testcaseAssetId">
              <option value="">不选择测试用例资料</option>
              <option v-for="asset in overview.testcases" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
            </select>
          </label>
          <label>
            <span>选择源码资料版本</span>
            <select v-model="baselineForm.sourceAssetId">
              <option value="">使用应用静态索引或暂不选择</option>
              <option v-for="asset in overview.sources" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
            </select>
          </label>
          <label>
            <span>应用静态索引</span>
            <select v-model="baselineForm.sourceAppId">
              <option value="">不绑定应用</option>
              <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
            </select>
          </label>
          <div class="inline-grid">
            <label>
              <span>执行依据</span>
              <select v-model="baselineForm.executionAssetId">
                <option value="">未选择</option>
                <option v-for="asset in overview.executions" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
              </select>
            </label>
            <label>
              <span>覆盖率依据</span>
              <select v-model="baselineForm.coverageAssetId">
                <option value="">未选择</option>
                <option v-for="asset in overview.coverages" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
              </select>
            </label>
          </div>
          <div class="inline-grid">
            <label>
              <span>分支</span>
              <input v-model.trim="baselineForm.sourceBranch" type="text" placeholder="main / release-2.0" />
            </label>
            <label>
              <span>Commit</span>
              <input v-model.trim="baselineForm.sourceCommit" type="text" placeholder="完整 commit SHA" />
            </label>
          </div>
          <div class="inline-actions">
            <button v-if="editingBaselineId" type="button" class="secondary-button" @click="cancelEditBaseline">取消编辑</button>
            <button type="button" class="primary-button full" :disabled="creatingBaseline" @click="saveBaseline">
              {{ creatingBaseline ? '保存中...' : editingBaselineId ? '保存基线修改' : '创建分析基线' }}
            </button>
          </div>
        </div>
      </section>

      <section class="panel-section baseline-list">
        <div class="section-head">
          <h2>分析基线列表</h2>
          <span>{{ overview.baselines.length }} 个</span>
        </div>
        <p class="section-tip">选择一个基线后进入“分析结果”查看追溯矩阵和 AI 发现。</p>
        <article
          v-for="baseline in overview.baselines"
          :key="baseline.id"
          class="baseline-item"
          :class="{ active: baseline.id === selectedBaselineId }"
        >
          <button type="button" class="record-main" @click="openBaseline(baseline.id)">
            <strong>{{ baseline.name }}</strong>
            <span>{{ baselineStatusText(baseline.status) }} · {{ freshnessText(baseline.freshness) }} · {{ formatTime(baseline.createTime) }}</span>
          </button>
          <div class="record-actions">
            <button type="button" @click="startEditBaseline(baseline)">编辑</button>
            <button type="button" class="danger-button" @click="deleteBaseline(baseline)">删除</button>
          </div>
        </article>
        <div v-if="!overview.baselines.length" class="empty-state compact">
          <strong>还没有分析基线</strong>
          <span>选择需要锁定的资料后创建基线。</span>
        </div>
      </section>
    </section>

    <section v-else class="workspace-section">
      <section v-if="!detail" class="panel-section empty-state">
        <strong>还没有选择分析基线</strong>
        <span>请先到“分析基线”选择或创建一个基线。</span>
      </section>
      <template v-else>
        <section class="result-toolbar">
          <div>
            <strong>{{ detail.baseline.name }}</strong>
            <span>{{ baselineStatusText(detail.baseline.status) }} · {{ freshnessText(detail.baseline.freshness) }} · {{ formatTime(detail.baseline.updateTime) }}</span>
            <small v-if="analysisJob">{{ analysisJobStatusText(analysisJob.status) }} · {{ analysisJob.message || '-' }}</small>
            <div v-if="analysisJob && (analysisJob.status === 'QUEUED' || analysisJob.status === 'RUNNING')" class="analysis-progress" aria-live="polite">
              <div class="analysis-progress-track"><span :style="{ width: `${analysisProgress(analysisJob)}%` }"></span></div>
              <small>{{ analysisProgress(analysisJob) }}% · {{ analysisPhase(analysisJob) }}</small>
            </div>
          </div>
          <div class="header-actions">
            <button type="button" class="secondary-button" :disabled="!selectedBaselineId" @click="markStale">标记过期</button>
            <button type="button" class="primary-button" :disabled="!selectedBaselineId || analyzing" @click="runAnalysis">
              {{ analyzing ? '分析中...' : '运行 AI 分析' }}
            </button>
          </div>
        </section>

        <section class="metrics-strip">
          <article>
            <span class="with-help" :data-help="helpText.requirementCount" tabindex="0">需求数量</span>
            <strong>{{ analysisCounts.requirements }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.testcaseCount" tabindex="0">测试用例</span>
            <strong>{{ analysisCounts.testcases }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.defectCount" tabindex="0">缺陷(Bug)</span>
            <strong>{{ analysisCounts.defects }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.staticCodeCount" tabindex="0">静态代码</span>
            <strong>{{ analysisCounts.staticCode }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.dynamicCodeCount" tabindex="0">动态代码</span>
            <strong>{{ analysisCounts.dynamicCode }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.testcaseCoverage" tabindex="0">用例覆盖</span>
            <strong>{{ percent(detail.metrics.testcaseCoverageRate) }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.implementationEvidence" tabindex="0">实现依据</span>
            <strong>{{ percent(detail.metrics.implementationCoverageRate) }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.executionEvidence" tabindex="0">执行依据</span>
            <strong>{{ percent(detail.metrics.executionEvidenceRate) }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.runtimeCoverage" tabindex="0">运行覆盖</span>
            <strong>{{ percent(detail.metrics.runtimeCoverageRate) }}</strong>
          </article>
          <article>
            <span class="with-help" :data-help="helpText.openFindings" tabindex="0">开放问题</span>
            <strong>{{ detail.metrics.openFindings }}</strong>
          </article>
        </section>

        <nav class="tabs" aria-label="验证视图">
          <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">
            {{ tab.label }}
          </button>
        </nav>

        <section v-if="activeTab === 'matrix'" class="tab-content">
          <div class="list-toolbar">
            <input v-model.trim="matrixSearch" type="search" placeholder="搜索验收标准、测试用例、依据或结论" />
            <select v-model="matrixVerdict">
              <option value="">全部结论</option>
              <option v-for="option in matrixVerdictOptions" :key="option" :value="option">{{ verdictText(option) }}</option>
            </select>
            <select v-model="matrixEvidenceLevel">
              <option value="">全部依据等级</option>
              <option v-for="option in evidenceLevelOptions" :key="option" :value="option">{{ evidenceLevelText(option) }}</option>
            </select>
            <select v-model="matrixTraceTargetType">
              <option value="">全部依据类型</option>
              <option v-for="option in traceTargetTypeOptions" :key="option" :value="option">{{ traceTargetText(option) }}</option>
            </select>
            <select v-model="matrixTraceReviewStatus">
              <option value="">全部追溯状态</option>
              <option v-for="option in traceReviewStatusOptions" :key="option" :value="option">{{ reviewStatusText(option) }}</option>
            </select>
            <span>共 {{ filteredMatrix.length }} 条</span>
          </div>
          <div class="matrix-table">
          <div class="table-row table-head">
            <span>验收标准</span>
            <span>测试用例</span>
            <span>代码/运行依据</span>
            <span>结论</span>
          </div>
          <article v-for="row in pagedMatrix" :key="row.criterion.id" class="table-row">
            <div>
              <strong>{{ row.criterion.requirementKey }} / {{ row.criterion.acKey }}</strong>
              <p>{{ row.criterion.content }}</p>
            </div>
            <div class="chips">
              <span v-for="testcase in row.testcases" :key="testcase.id">{{ testcase.externalKey }}</span>
              <em v-if="!row.testcases.length">未覆盖</em>
            </div>
            <div class="trace-list">
              <button
                v-for="link in evidenceLinks(row.criterion.id)"
                :key="link.id"
                type="button"
                :title="JSON.stringify(link.evidence || {})"
                @click="confirmTrace(link.id)"
              >
                  {{ traceTargetText(link.targetType) }} · {{ evidenceLevelText(link.evidenceLevel) }} · {{ reviewStatusText(link.reviewStatus) }}
                </button>
              </div>
              <div>
                <span class="verdict" :class="row.verdict.toLowerCase()">{{ verdictText(row.verdict) }}</span>
              <small class="with-help" :data-help="evidenceLevelHelp(row.evidenceLevel)" tabindex="0">{{ evidenceLevelText(row.evidenceLevel) }}</small>
              </div>
          </article>
          <div v-if="!filteredMatrix.length" class="empty-state compact">没有匹配的追溯数据。</div>
          </div>
          <AppPagination
            v-if="filteredMatrix.length"
            v-model:page="matrixPage"
            v-model:page-size="matrixPageSize"
            :total="filteredMatrix.length"
            item-name="条追溯记录"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section v-else-if="activeTab === 'findings'" class="finding-list">
          <div class="finding-toolbar">
            <input v-model.trim="findingSearch" type="search" placeholder="搜索问题标题、描述、类型、角色、结论或依据" />
            <select v-model="findingPerspective">
              <option value="">全部视角</option>
              <option value="PRODUCT">产品</option>
              <option value="TEST">测试</option>
              <option value="DEVELOPMENT">开发</option>
              <option value="CROSS">交叉</option>
            </select>
            <select v-model="findingSeverity">
              <option value="">全部严重程度</option>
              <option v-for="option in findingSeverityOptions" :key="option" :value="option">{{ severityText(option) }}</option>
            </select>
            <select v-model="findingReviewStatus">
              <option value="">全部审核状态</option>
              <option v-for="option in findingReviewStatusOptions" :key="option" :value="option">{{ reviewStatusText(option) }}</option>
            </select>
            <select v-model="findingVerdict">
              <option value="">全部结论</option>
              <option v-for="option in findingVerdictOptions" :key="option" :value="option">{{ verdictText(option) }}</option>
            </select>
            <select v-model="findingType">
              <option value="">全部问题类型</option>
              <option v-for="option in findingTypeOptions" :key="option" :value="option">{{ findingTypeText(option) }}</option>
            </select>
          </div>
          <article v-for="finding in pagedFindings" :key="finding.id" class="finding-card" :class="finding.severity.toLowerCase()">
            <div class="finding-main">
              <span>
                {{ perspectiveText(finding.perspective) }} · {{ severityText(finding.severity) }} · {{ reviewStatusText(finding.reviewStatus) }}
                · {{ verdictText(finding.verdict) }} · {{ evidenceLevelText(finding.evidenceLevel) }} · {{ findingTypeText(finding.findingType) }}
              </span>
              <h3>{{ finding.title }}</h3>
              <p>{{ finding.description }}</p>
              <small v-if="finding.suggestion">建议：{{ finding.suggestion }}</small>
              <div v-if="findingContextItems(finding).length" class="finding-context">
                <span v-for="item in findingContextItems(finding)" :key="item.key">
                  {{ item.label }}：{{ item.value }}
                </span>
              </div>
              <a v-if="finding.externalWorkItemUrl" :href="finding.externalWorkItemUrl" target="_blank" rel="noreferrer">外部事项</a>
            </div>
            <div class="finding-actions">
              <button type="button" @click="reviewFinding(finding.id, 'CONFIRMED')">确认</button>
              <button type="button" @click="reviewFinding(finding.id, 'REJECTED')">驳回</button>
              <button type="button" @click="reviewFinding(finding.id, 'EXEMPTED')">豁免</button>
              <button type="button" @click="startWriteBack(finding.id)">AI 生成回写</button>
            </div>
            <form v-if="writeBackFindingId === finding.id" class="writeback-form" @submit.prevent="writeBackFinding(finding.id)">
              <div class="writeback-intro">
                <strong>让 AI 生成对方可直接处理的内容</strong>
                <span>AI 会结合该问题、依据、关联验收标准和测试用例，生成外部 Bug / 任务 / 评论可直接使用的说明。</span>
              </div>
              <label>
                <span>接收方</span>
                <select v-model="writeBackTargetRole">
                  <option value="CROSS">综合协同</option>
                  <option value="PRODUCT">产品处理</option>
                  <option value="TEST">测试处理</option>
                  <option value="DEVELOPMENT">开发处理</option>
                </select>
              </label>
              <label>
                <span>外部 Bug / 任务 / 评论链接</span>
                <input v-model.trim="writeBackUrl" type="url" placeholder="已有外部事项时填写 https://..." autocomplete="off" />
              </label>
              <label>
                <span>补充说明</span>
                <textarea v-model.trim="writeBackNote" rows="3" placeholder="例如：希望生成给开发的修复说明，或说明对方平台的任务背景..." />
              </label>
              <div class="writeback-actions">
                <button type="button" class="secondary-button" @click="cancelWriteBack">取消</button>
                <button type="submit" class="primary-button" :disabled="writeBackSubmitting">
                  {{ writeBackSubmitting ? 'AI 生成中...' : '生成并记录' }}
                </button>
              </div>
            </form>
          </article>
          <div v-if="!filteredFindings.length" class="empty-state compact">当前筛选下没有问题。</div>
          <AppPagination
            v-if="filteredFindings.length"
            v-model:page="findingPage"
            v-model:page-size="findingPageSize"
            :total="filteredFindings.length"
            item-name="个问题"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section v-else class="evidence-panel">
          <div class="list-toolbar">
            <input v-model.trim="evidenceSearch" type="search" placeholder="搜索依据记录、状态、连接器或内容" />
            <select v-model="evidenceSource">
              <option value="">全部来源</option>
              <option value="TRACE">追溯关系</option>
              <option value="FINDING">AI 发现依据</option>
              <option value="WRITEBACK">AI 回写记录</option>
            </select>
            <select v-model="evidenceTargetType">
              <option value="">全部依据类型</option>
              <option v-for="option in evidenceTargetTypeOptions" :key="option" :value="option">{{ evidenceRecordTypeText(option) }}</option>
            </select>
            <select v-model="evidenceLevelFilter">
              <option value="">全部依据等级</option>
              <option v-for="option in evidenceLevelOptions" :key="option" :value="option">{{ evidenceLevelText(option) }}</option>
            </select>
            <select v-model="evidenceReviewStatus">
              <option value="">全部状态</option>
              <option v-for="option in evidenceReviewStatusOptions" :key="option" :value="option">{{ evidenceStatusText(option) }}</option>
            </select>
            <span>共 {{ filteredEvidenceRecords.length }} 条依据记录</span>
          </div>
          <article>
            <strong>输入新鲜度</strong>
              <span>{{ freshnessText(detail.baseline.freshness) }} · 分析器 {{ detail.baseline.analyzerVersion }}</span>
          </article>
          <article>
            <strong>源代码版本</strong>
            <span>{{ detail.baseline.sourceBranch || '-' }} / {{ detail.baseline.sourceCommit || '-' }}</span>
          </article>
          <article>
              <strong class="with-help" :data-help="helpText.conclusionScope" tabindex="0">结论口径</strong>
              <span>没有测试执行或覆盖率依据时，只能判断“静态一致”，不能判断“已完整满足”。</span>
          </article>
          <article v-for="record in pagedEvidenceRecords" :key="record.id" class="evidence-record">
            <div class="evidence-heading">
              <strong>{{ record.title }}</strong>
              <button v-if="record.copyText" type="button" class="ghost-button" @click="copyWriteBack(record.copyText)">复制内容</button>
            </div>
            <span>{{ record.meta }}</span>
            <p v-if="record.summary">{{ record.summary }}</p>
            <a v-if="record.url" :href="record.url" target="_blank" rel="noreferrer">{{ record.url }}</a>
            <pre v-if="record.detail" class="writeback-message">{{ record.detail }}</pre>
          </article>
          <div v-if="!filteredEvidenceRecords.length" class="empty-state compact">没有匹配的依据记录。</div>
          <AppPagination
            v-if="filteredEvidenceRecords.length"
            v-model:page="evidencePage"
            v-model:page-size="evidencePageSize"
            :total="filteredEvidenceRecords.length"
            item-name="条依据记录"
            :page-sizes="[10, 20, 50]"
          />
        </section>
      </template>
    </section>
    <Teleport to="body">
      <div
        v-if="helpTooltip.visible"
        ref="helpTooltipRef"
        class="adaptive-help-tooltip"
        :class="helpTooltip.placement"
        :style="{
          left: `${helpTooltip.left}px`,
          top: `${helpTooltip.top}px`,
          width: `${helpTooltip.width}px`,
          '--arrow-left': `${helpTooltip.arrowLeft}px`,
        }"
        role="tooltip"
      >
        {{ helpTooltip.text }}
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useRoute } from 'vue-router'

import {
  analyzeBaseline,
  createVerificationBaseline,
  deleteVerificationAsset,
  deleteVerificationBaseline,
  fetchWriteBackActions,
  fetchAnalysisJob,
  fetchBaselineDetail,
  fetchLatestAnalysisJob,
  fetchTraceMatrix,
  fetchVerificationOverview,
  importGitSourceAsset,
  importVerificationAsset,
  markVerificationBaselineStale,
  reviewTraceLink,
  reviewVerificationFinding,
  updateVerificationAsset,
  updateVerificationBaseline,
  writeBackVerificationFinding,
  type AnalysisJob,
  type AssetType,
  type BaselineDetail,
  type EvidenceLevel,
  type MatrixRow,
  type ReviewStatus,
  type Severity,
  type TraceLink,
  type VerificationAsset,
  type VerificationBaseline,
  type VerificationFinding,
  type VerificationOverview,
  type Verdict,
  type WriteBackAction,
} from '@/api/verification'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { useProjectStore } from '@/stores/project'
import type { RelationEdge, RelationNode } from '@/features/map/types'

type WorkspaceKey = 'library' | 'baseline' | 'result'
type EvidenceRecord = {
  id: string
  source: 'TRACE' | 'FINDING' | 'WRITEBACK'
  targetType?: string
  evidenceLevel?: string
  reviewStatus?: string
  title: string
  meta: string
  summary: string
  detail?: string
  url?: string
  copyText?: string
  searchable: string
}

const route = useRoute()
const toast = useToast()
const dialog = useDialog()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.contextByProjectId[projectId.value]?.apps || [])

const emptyOverview: VerificationOverview = { requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] }
const overview = ref<VerificationOverview>(emptyOverview)
const detail = ref<BaselineDetail | null>(null)
const matrix = ref<MatrixRow[]>([])
const writeBacks = ref<WriteBackAction[]>([])
const analysisJob = ref<AnalysisJob | null>(null)
const activeWorkspace = ref<WorkspaceKey>('library')
const selectedBaselineId = ref('')
const activeTab = ref<'matrix' | 'findings' | 'evidence'>('matrix')
const findingPerspective = ref('')
const findingSeverity = ref('')
const findingReviewStatus = ref('')
const findingVerdict = ref('')
const findingType = ref('')
const matrixVerdict = ref('')
const matrixEvidenceLevel = ref('')
const matrixTraceTargetType = ref('')
const matrixTraceReviewStatus = ref('')
const evidenceSource = ref('')
const evidenceTargetType = ref('')
const evidenceLevelFilter = ref('')
const evidenceReviewStatus = ref('')
const matrixSearch = ref('')
const findingSearch = ref('')
const evidenceSearch = ref('')
const matrixPage = ref(1)
const findingPage = ref(1)
const evidencePage = ref(1)
const matrixPageSize = ref(10)
const findingPageSize = ref(10)
const evidencePageSize = ref(10)
const loading = ref(false)
const analyzing = ref(false)
const creatingBaseline = ref(false)
const importing = ref<AssetType | ''>('')
const gitImporting = ref(false)
const assetUpdating = ref(false)
const expandedImportTypes = ref<AssetType[]>(['REQUIREMENT', 'TESTCASE'])
const editingAsset = ref<VerificationAsset | null>(null)
const editingBaselineId = ref('')
const writeBackFindingId = ref('')
const writeBackUrl = ref('')
const writeBackNote = ref('')
const writeBackTargetRole = ref<'PRODUCT' | 'TEST' | 'DEVELOPMENT' | 'CROSS'>('CROSS')
const writeBackSubmitting = ref(false)
let analysisPollTimer: ReturnType<typeof setTimeout> | null = null
const error = ref('')
const files = reactive<Partial<Record<AssetType, File>>>({})
const draggingAssetType = ref<AssetType | ''>('')
const pasteInputs = reactive<Record<AssetType, string>>({ REQUIREMENT: '', TESTCASE: '', SOURCE: '', EXECUTION: '', COVERAGE: '', DEFECT: '' })
const sourceVersions = reactive<Record<AssetType, string>>({ REQUIREMENT: '', TESTCASE: '', SOURCE: '', EXECUTION: '', COVERAGE: '', DEFECT: '' })
const baselineForm = reactive({
  name: '',
  requirementAssetId: '',
  testcaseAssetId: '',
  sourceAssetId: '',
  executionAssetId: '',
  coverageAssetId: '',
  sourceAppId: '',
  sourceBranch: '',
  sourceCommit: '',
})
const assetEditForm = reactive({
  fileName: '',
  sourceVersion: '',
  content: '',
})
const gitForm = reactive({
  appId: '',
  branch: '',
  commit: '',
  maxFiles: 1000,
})
const helpTooltipRef = ref<HTMLElement | null>(null)
const helpTooltip = reactive({
  visible: false,
  text: '',
  left: 0,
  top: 0,
  width: 320,
  arrowLeft: 24,
  placement: 'below' as 'above' | 'below',
})
let activeHelpElement: HTMLElement | null = null

const workspaceTabs: Array<{ key: WorkspaceKey; label: string; description: string }> = [
  { key: 'library', label: '资料库', description: '导入 / 查看资料' },
  { key: 'baseline', label: '分析基线', description: '选择资料并创建基线' },
  { key: 'result', label: '分析结果', description: '矩阵 / 问题 / 依据' },
]

const assetInputs: Array<{ type: AssetType; label: string; hint: string; placeholder: string }> = [
  { type: 'REQUIREMENT', label: '需求', hint: 'Word / Markdown / Excel / CSV / 文本', placeholder: '粘贴需求功能点或验收标准...' },
  { type: 'TESTCASE', label: '测试用例', hint: 'XMind脑图 / Excel / CSV / JSON / 文本', placeholder: '粘贴用例ID、步骤、预期结果...' },
  { type: 'SOURCE', label: '源码', hint: '上传源码包 / 粘贴源码 / 从源码工程导入', placeholder: '粘贴 Controller / Service / 核心逻辑...' },
  { type: 'DEFECT', label: '缺陷 / Bug', hint: 'Excel / CSV / JSON / 文本', placeholder: '粘贴 Bug、缺陷、生产问题或外部任务摘要...' },
  { type: 'EXECUTION', label: '执行报告', hint: 'Excel / CSV / JSON / 文本', placeholder: '粘贴测试执行结果，包含用例ID和状态...' },
  { type: 'COVERAGE', label: '覆盖率', hint: 'JaCoCo / Istanbul / LCOV / Cobertura / Go / Python 等', placeholder: '粘贴多语言覆盖率报告摘要...' },
]

const tabs = [
  { key: 'matrix', label: '追溯矩阵' },
  { key: 'findings', label: 'AI 发现' },
  { key: 'evidence', label: '依据与口径' },
] as const

const findingSeverityOptions: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const findingReviewStatusOptions: ReviewStatus[] = ['PENDING', 'CONFIRMED', 'REJECTED', 'WRITTEN_BACK', 'EXEMPTED', 'STALE']
const findingVerdictOptions: Verdict[] = ['NOT_SATISFIED', 'AMBIGUOUS', 'NOT_VERIFIABLE', 'PARTIAL', 'STATICALLY_CONSISTENT', 'SATISFIED', 'STALE', 'EXEMPTED']
const matrixVerdictOptions: Verdict[] = ['NOT_SATISFIED', 'AMBIGUOUS', 'NOT_VERIFIABLE', 'PARTIAL', 'STATICALLY_CONSISTENT', 'SATISFIED', 'STALE', 'EXEMPTED']
const evidenceLevelOptions: EvidenceLevel[] = ['E0', 'E1', 'E2', 'E3', 'E4']
const traceTargetTypeOptions = ['TESTCASE', 'SOURCE_SYMBOL', 'EXECUTION', 'COVERAGE', 'DEFECT'] as const
const traceReviewStatusOptions: ReviewStatus[] = ['PENDING', 'CONFIRMED', 'REJECTED', 'STALE', 'EXEMPTED']

const helpText = {
  ac: 'AC 是 Acceptance Criteria，指需求中的可验收标准。平台会按这些标准检查用例和代码是否覆盖。',
  requirementCount: 'AI 从需求资料中抽取并落库的验收标准数量。',
  testcaseCount: 'AI 从测试用例资料中抽取并落库的测试用例数量。',
  defectCount: '资料库中已导入的缺陷/Bug 资料数量。缺陷资料会进入 AI 分析依据池。',
  staticCodeCount: '当前基线导入的静态源码总量。Git 导入会统计仓库包中识别到的全部源码文件，不等于 AI 摘要采样数或追溯命中数。',
  dynamicCodeCount: '当前基线导入的动态代码依据数量，来自执行报告和覆盖率报告。',
  testcaseCoverage: '有多少验收标准找到了对应测试用例。低于 100% 说明测试用例需要补充。',
  implementationEvidence: '有多少验收标准在源码中找到了对应实现依据。找不到不一定代表没实现，但需要开发确认或补充关联。',
  executionEvidence: '有多少验收标准有测试执行记录支撑，例如测试报告、CI 结果。',
  runtimeCoverage: '有多少验收标准有覆盖率依据支撑，例如 JaCoCo、Istanbul 或流水线覆盖率。',
  openFindings: '仍处于待确认或已确认状态的问题数。驳回、豁免和已回写的问题不计入开放问题。',
  conclusionScope: '结论口径用于防止误判。只有静态依据时只能说“静态一致”，不能说线上一定满足需求。',
}

const analysisCounts = computed(() => {
  const current = detail.value
  if (!current) {
    return { requirements: 0, testcases: 0, defects: 0, staticCode: 0, dynamicCode: 0 }
  }
  return {
    requirements: current.criteria.length,
    testcases: current.testcases.length,
    defects: overview.value.defects.length,
    staticCode: current.metrics.staticCodeCount ?? 0,
    dynamicCode: current.metrics.dynamicCodeCount ?? 0,
  }
})

const traceMapNodes = computed<RelationNode[]>(() => {
  const current = detail.value
  if (!current) return []
  const nodes = new Map<string, RelationNode>()
  const testcaseById = new Map(current.testcases.map((item) => [item.id, item]))
  const matrixByAcId = new Map(matrix.value.map((row) => [row.criterion.id, row]))

  current.criteria.forEach((criterion) => {
    const row = matrixByAcId.get(criterion.id)
    nodes.set(traceNodeId('REQUIREMENT', criterion.id), {
      id: traceNodeId('REQUIREMENT', criterion.id),
      label: `${criterion.requirementKey} ${criterion.acKey}`,
      type: 'requirement',
      description: criterion.title || criterion.content,
      meta: [
        row ? `结论 ${verdictText(row.verdict)}` : '',
        row ? evidenceLevelText(row.evidenceLevel) : '',
        criterion.testable ? '可测试' : '不可测试',
        criterion.ambiguity ? '存在歧义' : '',
        criterion.sourceLocator ? `位置 ${criterion.sourceLocator}` : '',
      ].filter(Boolean),
    })
  })

  current.testcases.forEach((testcase) => {
    nodes.set(traceNodeId('TESTCASE', testcase.id), {
      id: traceNodeId('TESTCASE', testcase.id),
      label: testcase.externalKey || testcase.title || testcase.id,
      type: 'testcase',
      description: testcase.title,
      meta: [
        testcase.sourceLocator ? `位置 ${testcase.sourceLocator}` : '',
        testcase.requirementRefs ? `需求引用 ${testcase.requirementRefs}` : '',
      ].filter(Boolean),
    })
  })

  current.traceLinks.forEach((link) => {
    const targetNodeId = traceNodeId(link.targetType, link.targetId)
    if (!nodes.has(targetNodeId)) {
      const testcase = testcaseById.get(link.targetId)
      const evidence = link.evidence || {}
      nodes.set(targetNodeId, {
        id: targetNodeId,
        label: testcase?.externalKey || traceTargetNodeLabel(link),
        type: traceNodeType(link.targetType),
        description: testcase?.title || stringValue(evidence.reason) || stringValue(evidence.summary) || link.targetId,
        meta: [
          traceTargetText(link.targetType),
          evidenceLevelText(link.evidenceLevel),
          reviewStatusText(link.reviewStatus),
          `置信度 ${Math.round((link.confidence || 0) * 100)}%`,
          stringValue(evidence.locator) ? `位置 ${stringValue(evidence.locator)}` : '',
        ].filter(Boolean),
      })
    }
  })

  current.findings.forEach((finding) => {
    nodes.set(traceNodeId('FINDING', finding.id), {
      id: traceNodeId('FINDING', finding.id),
      label: finding.title,
      type: 'finding bug',
      description: finding.description,
      meta: [
        perspectiveText(finding.perspective),
        severityText(finding.severity),
        verdictText(finding.verdict),
        reviewStatusText(finding.reviewStatus),
      ].filter(Boolean),
    })
  })

  return Array.from(nodes.values())
})

const traceMapEdges = computed<RelationEdge[]>(() => {
  const current = detail.value
  if (!current) return []
  const edges = new Map<string, RelationEdge>()

  matrix.value.forEach((row) => {
    row.testcases.forEach((testcase) => {
      const id = `matrix-${row.criterion.id}-${testcase.id}`
      edges.set(id, {
        id,
        source: traceNodeId('REQUIREMENT', row.criterion.id),
        target: traceNodeId('TESTCASE', testcase.id),
        label: `验证 · ${evidenceLevelText(row.evidenceLevel)}`,
        action: 'covers',
        sourceLabel: `${row.criterion.requirementKey} ${row.criterion.acKey}`,
        targetLabel: testcase.externalKey || testcase.title,
      })
    })
  })

  current.traceLinks.forEach((link) => {
    const sourceType = link.sourceType || 'REQUIREMENT'
    const source = traceSourceNodeId(sourceType, link.sourceId)
    const target = traceNodeId(link.targetType, link.targetId)
    edges.set(`trace-${link.id}`, {
      id: `trace-${link.id}`,
      source,
      target,
      label: traceRelationLabel(link),
      action: link.relationType || link.targetType,
    })
  })

  current.findings.forEach((finding) => {
    if (!finding.acId) return
    edges.set(`finding-${finding.id}`, {
      id: `finding-${finding.id}`,
      source: traceNodeId('REQUIREMENT', finding.acId),
      target: traceNodeId('FINDING', finding.id),
      label: `${severityText(finding.severity)} · ${findingTypeText(finding.findingType)}`,
      action: finding.severity,
    })
  })

  return Array.from(edges.values()).filter((edge) =>
    traceMapNodes.value.some((node) => node.id === edge.source)
      && traceMapNodes.value.some((node) => node.id === edge.target),
  )
})

const filteredFindings = computed(() => {
  const findings = detail.value?.findings || []
  const query = findingSearch.value.toLowerCase()
  return findings.filter((finding) => {
    const matchesPerspective = !findingPerspective.value || finding.perspective === findingPerspective.value
    const matchesSeverity = !findingSeverity.value || finding.severity === findingSeverity.value
    const matchesReviewStatus = !findingReviewStatus.value || finding.reviewStatus === findingReviewStatus.value
    const matchesVerdict = !findingVerdict.value || finding.verdict === findingVerdict.value
    const matchesType = !findingType.value || finding.findingType === findingType.value
    const searchable = [
      finding.title,
      finding.description,
      finding.suggestion,
      finding.findingType,
      findingTypeText(finding.findingType),
      perspectiveText(finding.perspective),
      severityText(finding.severity),
      reviewStatusText(finding.reviewStatus),
      verdictText(finding.verdict),
      evidenceLevelText(finding.evidenceLevel),
    ]
      .join(' ').toLowerCase()
    return matchesPerspective && matchesSeverity && matchesReviewStatus && matchesVerdict && matchesType
      && (!query || searchable.includes(query))
  })
})

const findingTypeOptions = computed(() => Array.from(new Set((detail.value?.findings || [])
  .map((finding) => finding.findingType)
  .filter(Boolean))).sort())

const filteredMatrix = computed(() => {
  const query = matrixSearch.value.toLowerCase()
  return matrix.value.filter((row) => {
    const links = evidenceLinks(row.criterion.id)
    const matchesVerdict = !matrixVerdict.value || row.verdict === matrixVerdict.value
    const matchesEvidenceLevel = !matrixEvidenceLevel.value || row.evidenceLevel === matrixEvidenceLevel.value
      || links.some((link) => link.evidenceLevel === matrixEvidenceLevel.value)
    const matchesTraceTargetType = !matrixTraceTargetType.value || links.some((link) => link.targetType === matrixTraceTargetType.value)
    const matchesTraceReviewStatus = !matrixTraceReviewStatus.value || links.some((link) => link.reviewStatus === matrixTraceReviewStatus.value)
    const searchable = [
      JSON.stringify(row),
      verdictText(row.verdict),
      evidenceLevelText(row.evidenceLevel),
      links.map((link) => `${traceTargetText(link.targetType)} ${evidenceLevelText(link.evidenceLevel)} ${reviewStatusText(link.reviewStatus)} ${link.targetId}`).join(' '),
    ].join(' ').toLowerCase()
    return matchesVerdict && matchesEvidenceLevel && matchesTraceTargetType && matchesTraceReviewStatus
      && (!query || searchable.includes(query))
  })
})

const evidenceRecords = computed<EvidenceRecord[]>(() => {
  const current = detail.value
  if (!current) return []
  const criteriaById = new Map(current.criteria.map((item) => [item.id, item]))
  const testcasesById = new Map(current.testcases.map((item) => [item.id, item]))
  const records: EvidenceRecord[] = []

  current.traceLinks.forEach((link) => {
    const criterion = criteriaById.get(link.sourceId)
    const target = testcasesById.get(link.targetId)
    const evidence = link.evidence || {}
    const reason = stringValue(evidence.reason) || stringValue(evidence.summary) || stringValue(evidence.value)
    const locator = stringValue(evidence.locator)
    const targetText = target ? `${target.externalKey} ${target.title}` : link.targetId
    const title = `${traceTargetText(link.targetType)}：${criterion?.acKey || '验收标准'} -> ${targetText}`
    const meta = [
      evidenceLevelText(link.evidenceLevel),
      reviewStatusText(link.reviewStatus),
      `置信度 ${Math.round((link.confidence || 0) * 100)}%`,
      link.generationMethod,
    ].filter(Boolean).join(' · ')
    const summary = reason || locator || criterion?.content || ''
    records.push({
      id: `trace-${link.id}`,
      source: 'TRACE',
      targetType: link.targetType,
      evidenceLevel: link.evidenceLevel,
      reviewStatus: link.reviewStatus,
      title,
      meta,
      summary,
      detail: locator && locator !== summary ? locator : undefined,
      searchable: [title, meta, summary, locator, criterion?.content].filter(Boolean).join(' '),
    })
  })

  current.findings.forEach((finding) => {
    const criterion = finding.acId ? criteriaById.get(finding.acId) : undefined
    const evidenceItems = finding.evidence?.length ? finding.evidence : []
    evidenceItems.forEach((item, index) => {
      const type = stringValue(item.type) || 'AI 发现依据'
      const evidenceId = stringValue(item.id)
      const locator = stringValue(item.locator)
      const summary = stringValue(item.summary) || finding.description
      const title = `${finding.title} · ${type}${evidenceId ? ` ${evidenceId}` : ''}`
      const meta = [
        perspectiveText(finding.perspective),
        severityText(finding.severity),
        evidenceLevelText(finding.evidenceLevel),
        reviewStatusText(finding.reviewStatus),
        criterion?.acKey,
      ].filter(Boolean).join(' · ')
      records.push({
        id: `finding-${finding.id}-${index}`,
        source: 'FINDING',
        targetType: type,
        evidenceLevel: finding.evidenceLevel,
        reviewStatus: finding.reviewStatus,
        title,
        meta,
        summary,
        detail: locator && locator !== summary ? locator : undefined,
        searchable: [title, meta, summary, locator, finding.suggestion, criterion?.content].filter(Boolean).join(' '),
      })
    })
  })

  writeBacks.value.forEach((action) => {
    const title = `AI 回写记录：${writeBackStatusText(action.status)}`
    const meta = `${action.connectorType} · ${formatTime(action.createTime)}`
    const summary = action.message || action.externalUrl || ''
    records.push({
      id: `writeback-${action.id}`,
      source: 'WRITEBACK',
      reviewStatus: action.status,
      title,
      meta,
      summary,
      detail: action.message,
      url: action.externalUrl,
      copyText: action.message,
      searchable: [title, meta, action.message, action.externalUrl].filter(Boolean).join(' '),
    })
  })

  return records
})

const filteredEvidenceRecords = computed(() => {
  const query = evidenceSearch.value.toLowerCase()
  return evidenceRecords.value.filter((record) => {
    const matchesSource = !evidenceSource.value || record.source === evidenceSource.value
    const matchesTargetType = !evidenceTargetType.value || record.targetType === evidenceTargetType.value
    const matchesEvidenceLevel = !evidenceLevelFilter.value || record.evidenceLevel === evidenceLevelFilter.value
    const matchesReviewStatus = !evidenceReviewStatus.value || record.reviewStatus === evidenceReviewStatus.value
    return matchesSource && matchesTargetType && matchesEvidenceLevel && matchesReviewStatus
      && (!query || record.searchable.toLowerCase().includes(query))
  })
})

const evidenceTargetTypeOptions = computed(() => Array.from(new Set(evidenceRecords.value
  .map((record) => record.targetType)
  .filter((value): value is string => Boolean(value)))).sort())
const evidenceReviewStatusOptions = computed(() => Array.from(new Set(evidenceRecords.value
  .map((record) => record.reviewStatus)
  .filter((value): value is string => Boolean(value)))).sort())

const matrixPageCount = computed(() => Math.max(1, Math.ceil(filteredMatrix.value.length / matrixPageSize.value)))
const findingPageCount = computed(() => Math.max(1, Math.ceil(filteredFindings.value.length / findingPageSize.value)))
const evidencePageCount = computed(() => Math.max(1, Math.ceil(filteredEvidenceRecords.value.length / evidencePageSize.value)))
const pagedMatrix = computed(() => pageSlice(filteredMatrix.value, matrixPage.value, matrixPageSize.value))
const pagedFindings = computed(() => pageSlice(filteredFindings.value, findingPage.value, findingPageSize.value))
const pagedEvidenceRecords = computed(() => pageSlice(filteredEvidenceRecords.value, evidencePage.value, evidencePageSize.value))

function pageSlice<T>(items: T[], page: number, size: number) {
  const start = (Math.max(1, page) - 1) * size
  return items.slice(start, start + size)
}

watch([
  matrixSearch,
  matrixVerdict,
  matrixEvidenceLevel,
  matrixTraceTargetType,
  matrixTraceReviewStatus,
  findingSearch,
  findingPerspective,
  findingSeverity,
  findingReviewStatus,
  findingVerdict,
  findingType,
  evidenceSearch,
  evidenceSource,
  evidenceTargetType,
  evidenceLevelFilter,
  evidenceReviewStatus,
], () => {
  matrixPage.value = 1
  findingPage.value = 1
  evidencePage.value = 1
})
watch(matrixPageCount, (count) => { matrixPage.value = Math.min(matrixPage.value, count) })
watch(findingPageCount, (count) => { findingPage.value = Math.min(findingPage.value, count) })
watch(evidencePageCount, (count) => { evidencePage.value = Math.min(evidencePage.value, count) })

watch([matrixPageSize, findingPageSize, evidencePageSize], () => {
  matrixPage.value = 1
  findingPage.value = 1
  evidencePage.value = 1
})

const assetGroups = computed<Array<{ key: AssetType; label: string; items: VerificationAsset[] }>>(() => [
  { key: 'REQUIREMENT', label: '需求资料', items: overview.value.requirements },
  { key: 'TESTCASE', label: '测试用例资料', items: overview.value.testcases },
  { key: 'SOURCE', label: '源码资料', items: overview.value.sources },
  { key: 'DEFECT', label: '缺陷资料', items: overview.value.defects },
  { key: 'EXECUTION', label: '执行依据', items: overview.value.executions },
  { key: 'COVERAGE', label: '覆盖率依据', items: overview.value.coverages },
])

const importedAssetCount = computed(() => assetGroups.value.reduce((total, group) => total + group.items.length, 0))
const visibleAssetGroups = computed(() => assetGroups.value.filter((group) => group.items.length > 0))

function showHelp(event: MouseEvent | FocusEvent) {
  const target = event.target instanceof Element ? event.target.closest<HTMLElement>('.with-help[data-help]') : null
  if (!target) return
  const text = target.dataset.help || ''
  if (!text) return
  activeHelpElement = target
  helpTooltip.text = text
  helpTooltip.visible = true
  void nextTick(positionHelpTooltip)
}

function hideHelpOnLeave(event: MouseEvent) {
  if (!activeHelpElement) return
  const nextTarget = event.relatedTarget instanceof Node ? event.relatedTarget : null
  if (nextTarget && activeHelpElement.contains(nextTarget)) return
  hideHelp()
}

function hideHelp() {
  activeHelpElement = null
  helpTooltip.visible = false
}

function positionHelpTooltip() {
  if (!activeHelpElement || !helpTooltip.visible) return
  const tooltip = helpTooltipRef.value
  if (!tooltip) return
  const margin = 12
  const gap = 10
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const width = Math.max(180, Math.min(320, viewportWidth - margin * 2))
  helpTooltip.width = width

  const triggerRect = activeHelpElement.getBoundingClientRect()
  const tooltipRect = tooltip.getBoundingClientRect()
  const tooltipHeight = tooltipRect.height || 48
  const triggerCenter = triggerRect.left + triggerRect.width / 2
  const minLeft = margin + width / 2
  const maxLeft = viewportWidth - margin - width / 2
  const left = clamp(triggerCenter, minLeft, Math.max(minLeft, maxLeft))
  const showBelow = triggerRect.bottom + gap + tooltipHeight <= viewportHeight - margin
    || triggerRect.top < tooltipHeight + gap + margin
  const top = showBelow
    ? Math.min(triggerRect.bottom + gap, viewportHeight - margin - tooltipHeight)
    : Math.max(margin, triggerRect.top - gap - tooltipHeight)
  const tooltipLeft = left - width / 2
  helpTooltip.left = left
  helpTooltip.top = top
  helpTooltip.placement = showBelow ? 'below' : 'above'
  helpTooltip.arrowLeft = clamp(triggerCenter - tooltipLeft, 16, width - 16)
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

onMounted(async () => {
  window.addEventListener('resize', positionHelpTooltip)
  window.addEventListener('scroll', positionHelpTooltip, true)
  await projectStore.loadProjectContext(projectId.value).catch(() => undefined)
  await loadOverview()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', positionHelpTooltip)
  window.removeEventListener('scroll', positionHelpTooltip, true)
  clearAnalysisPoll()
})

async function loadOverview() {
  if (!projectId.value) return
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchVerificationOverview(projectId.value)
    if (!selectedBaselineId.value && overview.value.baselines[0]) {
      await selectBaseline(overview.value.baselines[0].id)
      activeWorkspace.value = 'result'
    }
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function onFileChange(type: AssetType, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) setAssetFile(type, file)
  else delete files[type]
}

function setAssetFile(type: AssetType, file: File) {
  files[type] = file
  if (!isImportExpanded(type)) {
    expandedImportTypes.value = [...expandedImportTypes.value, type]
  }
}

function onAssetDragEnter(type: AssetType, event: DragEvent) {
  if (hasDraggedFiles(event)) {
    draggingAssetType.value = type
  }
}

function onAssetDragOver(type: AssetType, event: DragEvent) {
  if (!hasDraggedFiles(event)) return
  draggingAssetType.value = type
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onAssetDragLeave(event: DragEvent) {
  const current = event.currentTarget as HTMLElement | null
  const related = event.relatedTarget as Node | null
  if (current && related && current.contains(related)) return
  draggingAssetType.value = ''
}

function onAssetDrop(type: AssetType, event: DragEvent) {
  draggingAssetType.value = ''
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  if (!isAcceptedAssetFile(type, file)) {
    toast.warning(`${assetTypeLabel(type)}不支持该文件类型`)
    return
  }
  setAssetFile(type, file)
  toast.success(`已选择文件：${file.name}`)
}

function hasDraggedFiles(event: DragEvent) {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function isAcceptedAssetFile(type: AssetType, file: File) {
  const accept = assetAccept(type).split(',').map((item) => item.trim().toLowerCase()).filter(Boolean)
  if (!accept.length) return true
  const fileName = file.name.toLowerCase()
  return accept.some((item) => {
    if (item.startsWith('.')) return fileName.endsWith(item)
    return file.type === item || file.type.startsWith(item.replace('/*', '/'))
  })
}

function hasImportInput(type: AssetType) {
  return !!files[type] || !!pasteInputs[type]?.trim()
}

function isImportExpanded(type: AssetType) {
  return expandedImportTypes.value.includes(type)
}

function toggleImport(type: AssetType) {
  if (isImportExpanded(type)) {
    expandedImportTypes.value = expandedImportTypes.value.filter((item) => item !== type)
  } else {
    expandedImportTypes.value = [...expandedImportTypes.value, type]
  }
}

function importedCountByType(type: AssetType) {
  if (type === 'REQUIREMENT') return overview.value.requirements.length
  if (type === 'TESTCASE') return overview.value.testcases.length
  if (type === 'SOURCE') return overview.value.sources.length
  if (type === 'DEFECT') return overview.value.defects.length
  if (type === 'EXECUTION') return overview.value.executions.length
  if (type === 'COVERAGE') return overview.value.coverages.length
  return 0
}

function assetAccept(type: AssetType) {
  const map: Record<AssetType, string> = {
    REQUIREMENT: '.doc,.docx,.md,.markdown,.txt,.csv,.tsv,.xls,.xlsx,.json',
    TESTCASE: '.xmind,.mm,.opml,.xls,.xlsx,.csv,.tsv,.json,.txt,.md,.markdown',
    SOURCE: '.zip,.jar,.java,.kt,.js,.jsx,.ts,.tsx,.vue,.py,.go,.rs,.cs,.php,.rb,.xml,.yaml,.yml,.json,.properties',
    DEFECT: '.xls,.xlsx,.csv,.tsv,.json,.txt,.md,.markdown,.doc,.docx',
    EXECUTION: '.xls,.xlsx,.csv,.tsv,.json,.txt,.md,.markdown,.xml',
    COVERAGE: '.zip,.xml,.json,.info,.lcov,.out,.cov,.coverage,.csv,.tsv,.txt',
  }
  return map[type]
}

async function importAsset(type: AssetType) {
  if (!hasImportInput(type)) {
    toast.warning('请先选择文件或粘贴内容')
    return
  }
  importing.value = type
  error.value = ''
  try {
    const asset = await importVerificationAsset(projectId.value, type, files[type], pasteInputs[type], sourceVersions[type])
    selectAssetForBaseline(type, asset.id, false)
    toast.success(`${assetTypeLabel(type)}已导入，可在资料库查看`)
    pasteInputs[type] = ''
    files[type] = undefined
    await loadOverview()
    activeWorkspace.value = 'library'
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    importing.value = ''
  }
}

async function saveBaseline() {
  creatingBaseline.value = true
  try {
    const updating = !!editingBaselineId.value
    const baseline = editingBaselineId.value
      ? await updateVerificationBaseline(projectId.value, editingBaselineId.value, { ...baselineForm })
      : await createVerificationBaseline(projectId.value, { ...baselineForm })
    selectedBaselineId.value = baseline.id
    editingBaselineId.value = ''
    toast.success(updating ? '分析基线已更新' : '分析基线已创建')
    await loadOverview()
    await selectBaseline(baseline.id)
    activeWorkspace.value = 'result'
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    creatingBaseline.value = false
  }
}

function startEditAsset(asset: VerificationAsset) {
  editingAsset.value = asset
  assetEditForm.fileName = asset.fileName || asset.externalId || ''
  assetEditForm.sourceVersion = asset.sourceVersion || ''
  assetEditForm.content = ''
}

function cancelEditAsset() {
  editingAsset.value = null
  assetEditForm.fileName = ''
  assetEditForm.sourceVersion = ''
  assetEditForm.content = ''
}

async function saveAssetEdit() {
  if (!editingAsset.value) return
  assetUpdating.value = true
  try {
    await updateVerificationAsset(projectId.value, editingAsset.value.id, {
      fileName: assetEditForm.fileName,
      sourceVersion: assetEditForm.sourceVersion,
      content: assetEditForm.content || undefined,
    })
    toast.success('资料已更新')
    cancelEditAsset()
    await loadOverview()
  } catch (err) {
    toast.error(messageOf(err))
  } finally {
    assetUpdating.value = false
  }
}

async function deleteAsset(asset: VerificationAsset) {
  const confirmed = await dialog.confirm({
    title: '删除资料',
    message: `确认删除“${assetDisplayName(asset)}”？已被分析基线引用的资料不能删除。`,
    confirmText: '删除',
    tone: 'danger',
  })
  if (!confirmed) return
  try {
    await deleteVerificationAsset(projectId.value, asset.id)
    if (editingAsset.value?.id === asset.id) cancelEditAsset()
    toast.success('资料已删除')
    await loadOverview()
  } catch (err) {
    toast.error(messageOf(err))
  }
}

function startEditBaseline(baseline: VerificationBaseline) {
  editingBaselineId.value = baseline.id
  baselineForm.name = baseline.name || ''
  baselineForm.requirementAssetId = baseline.requirementAssetId || ''
  baselineForm.testcaseAssetId = baseline.testcaseAssetId || ''
  baselineForm.sourceAssetId = baseline.sourceAssetId || ''
  baselineForm.executionAssetId = baseline.executionAssetId || ''
  baselineForm.coverageAssetId = baseline.coverageAssetId || ''
  baselineForm.sourceAppId = baseline.sourceAppId || ''
  baselineForm.sourceBranch = baseline.sourceBranch || ''
  baselineForm.sourceCommit = baseline.sourceCommit || ''
}

function cancelEditBaseline() {
  editingBaselineId.value = ''
  baselineForm.name = ''
  baselineForm.requirementAssetId = ''
  baselineForm.testcaseAssetId = ''
  baselineForm.sourceAssetId = ''
  baselineForm.executionAssetId = ''
  baselineForm.coverageAssetId = ''
  baselineForm.sourceAppId = ''
  baselineForm.sourceBranch = ''
  baselineForm.sourceCommit = ''
}

async function deleteBaseline(baseline: VerificationBaseline) {
  const confirmed = await dialog.confirm({
    title: '删除分析基线',
    message: `确认删除“${baseline.name}”？相关分析结果、门禁结果和回写记录会一起删除。`,
    confirmText: '删除',
    tone: 'danger',
  })
  if (!confirmed) return
  try {
    await deleteVerificationBaseline(projectId.value, baseline.id)
    if (selectedBaselineId.value === baseline.id) {
      selectedBaselineId.value = ''
      detail.value = null
      matrix.value = []
      writeBacks.value = []
    }
    if (editingBaselineId.value === baseline.id) cancelEditBaseline()
    toast.success('分析基线已删除')
    await loadOverview()
  } catch (err) {
    toast.error(messageOf(err))
  }
}

async function importGitSource() {
  if (!gitForm.appId) {
    toast.warning('请先选择已配置仓库的源码工程')
    return
  }
  gitImporting.value = true
  error.value = ''
  try {
    const asset = await importGitSourceAsset(projectId.value, {
      appId: gitForm.appId,
      branch: gitForm.branch,
      commit: gitForm.commit,
      maxFiles: gitForm.maxFiles || 1000,
      maxBytes: 20000000,
    })
    baselineForm.sourceAssetId = asset.id
    if (gitForm.appId) baselineForm.sourceAppId = gitForm.appId
    baselineForm.sourceBranch = gitForm.branch
    baselineForm.sourceCommit = asset.sourceVersion || gitForm.commit
    toast.success('Git 源码已导入，可在资料库查看')
    await loadOverview()
    activeWorkspace.value = 'library'
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    gitImporting.value = false
  }
}

async function openBaseline(id: string) {
  await selectBaseline(id)
  activeWorkspace.value = 'result'
}

async function selectBaseline(id: string) {
  clearAnalysisPoll()
  selectedBaselineId.value = id
  detail.value = await fetchBaselineDetail(projectId.value, id)
  matrix.value = await fetchTraceMatrix(projectId.value, id)
  writeBacks.value = await fetchWriteBackActions(projectId.value, id)
  if (detail.value.baseline.status === 'ANALYZING') {
    await resumeAnalysisPolling(id)
  }
}

async function runAnalysis() {
  if (!selectedBaselineId.value) return
  analyzing.value = true
  try {
    analysisJob.value = await analyzeBaseline(projectId.value, selectedBaselineId.value)
    await loadOverview()
    activeWorkspace.value = 'result'
    toast.success('AI 分析任务已创建，完成后会自动刷新结果')
    pollAnalysisJob(analysisJob.value.id)
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
    analyzing.value = false
  }
}

function pollAnalysisJob(jobId: string) {
  clearAnalysisPoll()
  analysisPollTimer = setTimeout(async () => {
    try {
      const job = await fetchAnalysisJob(projectId.value, jobId)
      analysisJob.value = job
      if (job.status === 'SUCCEEDED') {
        analyzing.value = false
        toast.success('AI 一致性分析完成')
        await selectBaseline(job.baselineId)
        await loadOverview()
        return
      }
      if (job.status === 'FAILED') {
        analyzing.value = false
        toast.error(job.message || 'AI 分析失败')
        await loadOverview()
        if (selectedBaselineId.value) await selectBaseline(selectedBaselineId.value)
        return
      }
      pollAnalysisJob(jobId)
    } catch (err) {
      analyzing.value = false
      toast.error(messageOf(err))
    }
  }, 2000)
}

async function resumeAnalysisPolling(baselineId: string) {
  try {
    const job = await fetchLatestAnalysisJob(projectId.value, baselineId)
    analysisJob.value = job
    if (job.status === 'QUEUED' || job.status === 'RUNNING') {
      analyzing.value = true
      pollAnalysisJob(job.id)
    }
  } catch {
    analysisJob.value = null
  }
}

function clearAnalysisPoll() {
  if (analysisPollTimer) {
    clearTimeout(analysisPollTimer)
    analysisPollTimer = null
  }
}

function analysisJobStatusText(value?: string) {
  const map: Record<string, string> = {
    QUEUED: 'AI 分析排队中',
    RUNNING: 'AI 分析执行中',
    SUCCEEDED: 'AI 分析完成',
    FAILED: 'AI 分析失败',
  }
  return value ? map[value] || value : '-'
}

function analysisProgress(job: AnalysisJob) {
  if (job.status === 'QUEUED') return 5
  const message = job.message || ''
  if (message.includes('准备') || message.includes('读取需求')) return 20
  if (message.includes('源码') || message.includes('依据')) return 35
  if (message.includes('请求 AI') || message.includes('生成')) return 55
  if (message.includes('格式') || message.includes('校验') || message.includes('修复')) return 75
  if (message.includes('保存')) return 90
  return 50
}

function analysisPhase(job: AnalysisJob) {
  if (job.status === 'QUEUED') return '等待分析任务开始'
  return job.message || '正在处理分析结果'
}

function perspectiveText(value: string) {
  const map: Record<string, string> = {
    PRODUCT: '产品',
    TEST: '测试',
    DEVELOPMENT: '开发',
    CROSS: '交叉',
  }
  return map[value] || value
}

function severityText(value: string) {
  const map: Record<string, string> = {
    CRITICAL: '严重',
    HIGH: '高',
    MEDIUM: '中',
    LOW: '低',
    INFO: '提示',
  }
  return map[value] || value
}

function findingTypeText(value: string) {
  const map: Record<string, string> = {
    SATISFIED_SUMMARY: '满足结论',
    MISSING_TESTCASE: '缺少测试用例',
    WEAK_ASSERTION: '断言不足',
    WRONG_EXPECTATION: '预期错误',
    MISSING_IMPLEMENTATION: '缺少源码实现',
    MISSING_IMPLEMENTATION_EVIDENCE: '缺少源码关联依据',
    LOGIC_DEVIATION: '实现偏差',
    AMBIGUOUS_REQUIREMENT: '需求不明确',
    REQUIREMENT_CONFIRMATION: '需求口径确认',
    TRACEABILITY_BREAK: '追溯断点',
    MISSING_EVIDENCE: '缺少依据',
    MISSING_RUNTIME_EVIDENCE: '缺少执行/覆盖依据',
    ORPHAN_TESTCASE: '用例未关联',
    ORPHAN_SOURCE: '代码未关联',
    BUG_RISK: '缺陷风险',
    OTHER: '其他问题',
  }
  return value ? map[value] || value : '-'
}

function findingContextItems(finding: VerificationFinding) {
  const items: Array<{ key: string; label: string; value: string }> = []
  const criterion = finding.acId ? detail.value?.criteria.find((item) => item.id === finding.acId) : undefined
  if (criterion) {
    items.push({
      key: `criterion-${criterion.id}`,
      label: '需求',
      value: `${criterion.requirementKey} / ${criterion.acKey} ${criterion.title || criterion.content}`,
    })
  }
  ;(finding.evidence || []).forEach((item, index) => {
    const type = stringValue(item.type)
    const id = stringValue(item.id)
    const locator = stringValue(item.locator)
    const summary = stringValue(item.summary)
    const value = [id, locator, summary].filter(Boolean).join(' · ')
    if (!value) return
    items.push({
      key: `evidence-${finding.id}-${index}`,
      label: evidenceRecordTypeText(type || 'AI 发现依据'),
      value,
    })
  })
  return items
}

async function markStale() {
  if (!selectedBaselineId.value) return
  await markVerificationBaselineStale(projectId.value, selectedBaselineId.value)
  toast.warning('基线已标记过期，需重新导入或重新创建基线')
  await loadOverview()
  await selectBaseline(selectedBaselineId.value)
}

async function reviewFinding(id: string, status: ReviewStatus) {
  await reviewVerificationFinding(projectId.value, id, { status, reason: '前端工作台审核' })
  toast.success('审核状态已更新')
  await selectBaseline(selectedBaselineId.value)
}

function startWriteBack(id: string) {
  writeBackFindingId.value = id
  writeBackUrl.value = ''
  writeBackNote.value = ''
  writeBackTargetRole.value = 'CROSS'
}

function cancelWriteBack() {
  writeBackFindingId.value = ''
  writeBackUrl.value = ''
  writeBackNote.value = ''
  writeBackTargetRole.value = 'CROSS'
}

async function writeBackFinding(id: string) {
  writeBackSubmitting.value = true
  try {
    await writeBackVerificationFinding(projectId.value, id, {
      connectorType: 'ai-writeback',
      externalUrl: writeBackUrl.value || undefined,
      message: writeBackNote.value || undefined,
      targetRole: writeBackTargetRole.value,
    })
    toast.success('AI 回写内容已生成，可在依据页复制给对方处理')
    cancelWriteBack()
    await selectBaseline(selectedBaselineId.value)
    activeTab.value = 'evidence'
  } catch (err) {
    toast.error(messageOf(err))
  } finally {
    writeBackSubmitting.value = false
  }
}

async function copyWriteBack(message: string) {
  if (!message) {
    toast.warning('没有可复制的回写内容')
    return
  }
  try {
    await navigator.clipboard.writeText(message)
    toast.success('回写内容已复制')
  } catch {
    toast.error('浏览器禁止直接复制，请手动选中内容复制')
  }
}

function writeBackStatusText(status: string) {
  if (status === 'AI_GENERATED') return 'AI 已生成'
  if (status === 'RECORDED') return '已记录'
  return status || '-'
}

function stringValue(value: unknown) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function uniqueCount(values: Array<string | undefined>) {
  return new Set(values.filter((value): value is string => Boolean(value))).size
}

async function confirmTrace(id: string) {
  await reviewTraceLink(projectId.value, id, 'CONFIRMED')
  toast.success('追溯关系已确认')
  await selectBaseline(selectedBaselineId.value)
}

function evidenceLinks(acId: string): TraceLink[] {
  return detail.value?.traceLinks.filter((link) => link.sourceId === acId) || []
}

function traceNodeId(type: string, id: string) {
  return `${traceNodeType(type)}:${id}`
}

function traceSourceNodeId(type: string, id: string) {
  const normalized = traceNodeType(type)
  if (normalized === 'acceptance_criterion' || normalized === 'criterion') {
    return traceNodeId('REQUIREMENT', id)
  }
  return traceNodeId(type || 'REQUIREMENT', id)
}

function traceNodeType(type: string) {
  const value = String(type || '').toLowerCase()
  if (value.includes('requirement') || value.includes('acceptance') || value === 'ac') return 'requirement'
  if (value.includes('testcase') || value.includes('test_case')) return 'testcase'
  if (value.includes('source') || value.includes('symbol') || value.includes('code')) return 'source'
  if (value.includes('execution')) return 'execution'
  if (value.includes('coverage')) return 'coverage'
  if (value.includes('defect') || value.includes('bug') || value.includes('finding')) return 'bug'
  return value || 'node'
}

function traceTargetNodeLabel(link: TraceLink) {
  const evidence = link.evidence || {}
  return stringValue(evidence.name)
    || stringValue(evidence.symbol)
    || stringValue(evidence.locator)
    || `${traceTargetText(link.targetType)} ${shortId(link.targetId)}`
}

function traceRelationLabel(link: TraceLink) {
  return [
    relationTypeText(link.relationType),
    evidenceLevelText(link.evidenceLevel),
    reviewStatusText(link.reviewStatus),
  ].filter(Boolean).join(' · ')
}

function relationTypeText(value?: string) {
  const map: Record<string, string> = {
    VERIFIED_BY: '验证',
    IMPLEMENTED_BY: '实现',
    EXECUTED_BY: '执行',
    COVERED_BY: '覆盖',
    RELATED_TO: '关联',
    COVERS: '覆盖',
    IMPLEMENTS: '实现',
  }
  const key = String(value || '').toUpperCase()
  return map[key] || value || '关联'
}

function shortId(value?: string) {
  return value && value.length > 12 ? `${value.slice(0, 12)}...` : value || '-'
}

function assetLabel(asset: VerificationAsset) {
  return `${assetDisplayName(asset)} · ${asset.sourceType} · ${asset.sourceVersion || asset.contentHash.slice(0, 8)}`
}

function assetDisplayName(asset: VerificationAsset) {
  return asset.fileName || asset.externalId || `${assetTypeLabel(asset.assetType)} ${asset.id.slice(0, 8)}`
}

function assetTypeLabel(type: AssetType) {
  const map: Record<AssetType, string> = {
    REQUIREMENT: '需求资料',
    TESTCASE: '测试用例资料',
    SOURCE: '源码资料',
    EXECUTION: '执行依据',
    COVERAGE: '覆盖率依据',
    DEFECT: '缺陷资料',
  }
  return map[type] || type
}

function selectAssetForBaseline(type: AssetType, assetId: string, notify = true) {
  if (type === 'REQUIREMENT') baselineForm.requirementAssetId = assetId
  else if (type === 'TESTCASE') baselineForm.testcaseAssetId = assetId
  else if (type === 'SOURCE') baselineForm.sourceAssetId = assetId
  else if (type === 'EXECUTION') baselineForm.executionAssetId = assetId
  else if (type === 'COVERAGE') baselineForm.coverageAssetId = assetId
  else if (type === 'DEFECT') {
    if (notify) toast.success('缺陷资料已进入 AI 分析依据池，无需在基线中单独选择')
    return
  } else return

  if (notify) {
    activeWorkspace.value = 'baseline'
    toast.success(`已选择${assetTypeLabel(type)}用于分析基线`)
  }
}

function percent(value: number) {
  return `${Math.round((value || 0) * 1000) / 10}%`
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function baselineStatusText(value?: string) {
  const map: Record<string, string> = {
    CREATED: '待分析',
    ANALYZING: '分析中',
    WAITING_REVIEW: '待审核',
    COMPLETED: '已完成',
    FAILED: '分析失败',
    STALE: '已过期',
  }
  return value ? map[value] || value : '-'
}

function freshnessText(value?: string) {
  const map: Record<string, string> = {
    LIVE: '实时同步',
    SNAPSHOT: '固定版本',
    MANUAL: '人工导入',
    STALE: '已过期',
    UNKNOWN: '未知',
  }
  return value ? map[value] || value : '-'
}

function sourceTypeText(value?: string) {
  const map: Record<string, string> = {
    FILE: '文件导入',
    GIT: '源码工程',
    API: '接口同步',
    AGENT: '代理采集',
    PASTE: '粘贴导入',
  }
  return value ? map[value] || value : '-'
}

function storageText(value?: string) {
  const map: Record<string, string> = {
    MYSQL: 'MySQL 存储',
    FILE: '文件存储',
    OBJECT: '对象存储',
  }
  return value ? map[value] || value : '存储未标记'
}

function formatBytes(value?: number) {
  const size = value || 0
  if (size <= 0) return '大小未知'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${Math.round(size / 102.4) / 10} KB`
  return `${Math.round(size / 1024 / 102.4) / 10} MB`
}

function reviewStatusText(value?: string) {
  const map: Record<string, string> = {
    PENDING: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已驳回',
    WRITTEN_BACK: '已回写',
    STALE: '已过期',
    EXEMPTED: '已豁免',
  }
  return value ? map[value] || value : '-'
}

function traceTargetText(value?: string) {
  const map: Record<string, string> = {
    TESTCASE: '测试用例',
    SOURCE_SYMBOL: '源码依据',
    EXECUTION: '执行依据',
    COVERAGE: '覆盖率依据',
    DEFECT: '缺陷依据',
  }
  return value ? map[value] || value : '-'
}

function evidenceRecordTypeText(value?: string) {
  const map: Record<string, string> = {
    REQUIREMENT: '需求依据',
    TESTCASE: '测试用例',
    SOURCE: '源码依据',
    SOURCE_SYMBOL: '源码依据',
    EXECUTION: '执行依据',
    COVERAGE: '覆盖率依据',
    DEFECT: '缺陷依据',
    WRITEBACK: 'AI 回写',
  }
  return value ? map[value] || traceTargetText(value) : '-'
}

function evidenceStatusText(value?: string) {
  if (value === 'AI_GENERATED') return 'AI 已生成'
  if (value === 'RECORDED') return '已记录'
  return reviewStatusText(value)
}

function evidenceLevelText(value?: string) {
  const map: Record<string, string> = {
    E0: '无依据',
    E1: '用例依据',
    E2: '代码依据',
    E3: '执行依据',
    E4: '覆盖率依据',
  }
  return value ? map[value] || value : '-'
}

function evidenceLevelHelp(value?: string) {
  const map: Record<string, string> = {
    E0: '没有找到可验证该验收标准的依据，需要补充测试用例、源码关联或执行记录。',
    E1: '找到了测试用例依据，但还没有代码或运行依据。',
    E2: '找到了代码实现依据，可判断静态一致性。',
    E3: '找到了测试执行依据，说明相关用例实际运行过。',
    E4: '找到了覆盖率依据，证明相关代码路径被运行覆盖。',
  }
  return value ? map[value] || value : ''
}

function verdictText(value: Verdict) {
  const map: Record<Verdict, string> = {
    SATISFIED: '已满足',
    STATICALLY_CONSISTENT: '静态一致',
    PARTIAL: '部分满足',
    NOT_SATISFIED: '不满足',
    AMBIGUOUS: '需求模糊',
    NOT_VERIFIABLE: '不可验证',
    EXEMPTED: '已豁免',
    STALE: '已过期',
  }
  return map[value] || value
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '操作失败'
}
</script>

<style scoped>
.verification-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.verification-header,
.header-actions,
.section-head,
.gate-band,
.finding-toolbar,
.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.header-actions {
  flex-wrap: wrap;
}

.workspace-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.workspace-tabs button {
  display: grid;
  gap: 4px;
  min-height: 64px;
  text-align: left;
  border: 1px solid var(--oat-border);
  border-radius: 12px;
  padding: 12px;
  background: rgba(255, 255, 255, .92);
}

.workspace-tabs button.active {
  border-color: rgba(var(--oat-primary-rgb), .55);
  background: rgba(var(--oat-primary-rgb), .09);
  color: var(--oat-primary-dark);
}

.workspace-tabs span,
.section-head span,
.asset-group-head span,
.asset-record span,
.baseline-item span,
.empty-state span,
.gate-band span,
.result-toolbar span,
.finding-card small,
.evidence-panel span {
  color: var(--oat-text-muted);
  font-size: 12px;
}

.workspace-section {
  display: grid;
  gap: 14px;
}

.two-column {
  grid-template-columns: minmax(360px, .95fr) minmax(420px, 1.05fr);
  align-items: start;
}

.panel-section,
.notice,
.empty-state,
.gate-band,
.result-toolbar {
  border: 1px solid var(--oat-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, .94);
  padding: 14px;
}

.section-head h2 {
  margin: 0;
  font-size: 16px;
}

.section-tip {
  margin: 8px 0 12px;
  color: var(--oat-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.field-help {
  margin: 0;
  color: var(--oat-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.field-help a {
  margin-left: 6px;
  color: var(--oat-primary-dark);
  font-weight: 800;
}

.asset-import-grid,
.asset-library,
.form-stack,
.baseline-list,
.finding-list,
.evidence-panel {
  display: grid;
  gap: 10px;
}

.imported-assets {
  background: linear-gradient(180deg, rgba(var(--oat-primary-rgb), .04), rgba(255, 255, 255, .94));
}

.asset-group,
.asset-import,
.metrics-strip article,
.evidence-panel article {
  display: grid;
  gap: 6px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 10px;
  background: var(--oat-surface-soft);
}

.asset-group {
  background: #fff;
}

.asset-import.collapsed {
  background: #fff;
}

.asset-import.dragging {
  border-color: rgba(var(--oat-primary-rgb), .45);
  background: rgba(var(--oat-primary-rgb), .08);
  box-shadow: 0 0 0 3px rgba(var(--oat-primary-rgb), .10);
}

.asset-group-head,
.asset-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.asset-toggle {
  width: 100%;
  min-height: 42px;
  padding: 7px 8px;
  text-align: left;
}

.asset-record {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 8px;
  border-color: rgba(15, 23, 42, .08);
  background: var(--oat-surface-soft);
}

.record-main,
.asset-toggle {
  border: 0;
  background: transparent;
}

.asset-toggle span,
.record-main strong,
.record-main span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-main {
  display: grid;
  gap: 3px;
  min-height: auto;
  padding: 0;
  text-align: left;
}

.record-actions,
.inline-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.record-actions button {
  min-height: 30px;
  padding: 4px 8px;
  font-size: 12px;
}

.danger-button {
  border-color: rgba(220, 38, 38, .25);
  color: var(--oat-danger);
  background: rgba(220, 38, 38, .06);
}

.edit-panel {
  grid-column: 1 / -1;
}

.large-textarea {
  min-height: 180px;
}

.asset-toggle span {
  display: grid;
  gap: 2px;
}

.asset-toggle small {
  color: var(--oat-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.asset-toggle em {
  flex: 0 0 auto;
  color: var(--oat-primary-dark);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}

.asset-import-body {
  display: grid;
  gap: 8px;
}

.asset-file-drop {
  display: grid;
  gap: 4px;
  min-height: 74px;
  padding: 12px;
  border: 1px dashed rgba(100, 116, 139, .34);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, box-shadow .18s ease;
}

.asset-file-drop:hover,
.asset-file-drop:focus-within {
  border-color: rgba(var(--oat-primary-rgb), .45);
  background: rgba(var(--oat-primary-rgb), .05);
  box-shadow: 0 0 0 3px rgba(var(--oat-primary-rgb), .08);
}

.asset-file-drop input[type='file'] {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.asset-file-drop span {
  min-width: 0;
  overflow: hidden;
  color: var(--oat-text);
  font-size: 14px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-file-drop small {
  color: var(--oat-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.source-import-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--oat-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.source-import-divider::before,
.source-import-divider::after {
  content: '';
  height: 1px;
  flex: 1;
  background: var(--oat-border);
}

.git-source-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px dashed rgba(var(--oat-primary-rgb), .28);
  border-radius: 12px;
  background: rgba(var(--oat-primary-rgb), .04);
}

.compact-head {
  margin: 0;
}

.compact-head h3 {
  margin: 0;
  font-size: 15px;
}

textarea,
input,
select {
  width: 100%;
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff;
  color: var(--oat-text);
}

textarea {
  min-height: 78px;
  resize: vertical;
}

label {
  display: grid;
  gap: 5px;
}

label span {
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

button,
.primary-button,
.secondary-button {
  min-height: 36px;
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  padding: 7px 11px;
  background: #fff;
  color: var(--oat-text);
  font-weight: 800;
}

.primary-button {
  border-color: var(--oat-primary);
  background: var(--oat-primary);
  color: #fff;
}

.secondary-button {
  background: var(--oat-surface-soft);
}

.ghost-button {
  min-height: 30px;
  border-color: transparent;
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-primary);
  font-size: 12px;
}

.full {
  width: 100%;
}

.baseline-item {
  display: grid;
  gap: 4px;
  text-align: left;
}

.baseline-item.active {
  border-color: rgba(var(--oat-primary-rgb), .55);
  background: rgba(var(--oat-primary-rgb), .08);
}

.metrics-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.metrics-strip span {
  color: var(--oat-text-muted);
  font-size: 12px;
}

.metrics-strip strong {
  font-size: 20px;
}

.with-help {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: help;
  text-decoration: underline dotted rgba(15, 118, 110, .45);
  text-underline-offset: 3px;
}

.adaptive-help-tooltip {
  --arrow-left: 50%;
  position: fixed;
  z-index: 1000;
  padding: 8px 10px;
  border: 1px solid rgba(15, 118, 110, .22);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 12px 32px rgba(15, 23, 42, .16);
  color: var(--oat-text);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.55;
  overflow-wrap: anywhere;
  pointer-events: none;
  text-align: left;
  transform: translateX(-50%);
  white-space: normal;
}

.adaptive-help-tooltip::before {
  position: absolute;
  left: var(--arrow-left, 50%);
  width: 10px;
  height: 10px;
  border-right: 1px solid rgba(15, 118, 110, .22);
  border-bottom: 1px solid rgba(15, 118, 110, .22);
  background: #fff;
  content: '';
}

.adaptive-help-tooltip.above::before {
  bottom: -6px;
  transform: translateX(-50%) rotate(45deg);
}

.adaptive-help-tooltip.below::before {
  top: -6px;
  transform: translateX(-50%) rotate(225deg);
}

.analysis-progress {
  display: grid;
  grid-template-columns: minmax(160px, 280px) auto;
  align-items: center;
  gap: 8px;
  max-width: 560px;
  margin-top: 6px;
}

.analysis-progress-track {
  height: 5px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(15, 118, 110, .12);
}

.analysis-progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--oat-primary);
  transition: width .35s ease;
}

.gate-band.warning {
  border-color: rgba(217, 119, 6, .35);
  background: rgba(245, 158, 11, .08);
}

.gate-band.failed {
  border-color: rgba(220, 38, 38, .35);
  background: rgba(220, 38, 38, .07);
}

.gate-band.passed {
  border-color: rgba(22, 163, 74, .35);
  background: rgba(22, 163, 74, .07);
}

.tabs {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--oat-border);
}

.tabs button {
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.tabs button.active {
  border-color: var(--oat-primary);
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-primary-dark);
}

.tab-content {
  display: grid;
  gap: 10px;
}

.list-toolbar,
.finding-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.list-toolbar input,
.finding-toolbar input {
  flex: 1 1 280px;
  min-width: 220px;
}

.list-toolbar select,
.finding-toolbar select {
  flex: 0 1 150px;
  min-width: 132px;
}

.list-toolbar > span {
  color: var(--oat-text-muted);
  font-size: 12px;
}

.matrix-table {
  display: grid;
  gap: 0;
  border: 1px solid var(--oat-border);
  border-radius: 10px;
  overflow: visible;
}

.table-row {
  display: grid;
  grid-template-columns: 1.4fr .8fr 1fr .55fr;
  gap: 10px;
  padding: 10px;
  border-top: 1px solid var(--oat-border);
  background: #fff;
}

.table-row:first-child {
  border-top: 0;
}

.table-head {
  background: var(--oat-surface-soft);
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 800;
}

.table-row p,
.finding-card p {
  margin: 4px 0;
  color: var(--oat-text-secondary);
}

.chips,
.trace-list,
.finding-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: flex-start;
}

.chips span,
.chips em,
.verdict {
  border-radius: 999px;
  padding: 3px 8px;
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-primary-dark);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}

.trace-list button {
  min-height: 28px;
  padding: 4px 7px;
  font-size: 12px;
}

.verdict.not_satisfied,
.verdict.not_verifiable,
.verdict.ambiguous {
  background: rgba(220, 38, 38, .08);
  color: var(--oat-danger);
}

.verdict.statically_consistent,
.verdict.satisfied {
  background: rgba(22, 163, 74, .08);
  color: var(--oat-success);
}

.finding-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--oat-border);
  border-left: 4px solid var(--oat-warning);
  border-radius: 10px;
  background: #fff;
}

.finding-card.critical,
.finding-card.high {
  border-left-color: var(--oat-danger);
}

.finding-card.low,
.finding-card.info {
  border-left-color: var(--oat-info);
}

.finding-card h3 {
  margin: 4px 0;
  font-size: 16px;
}

.finding-main {
  min-width: 0;
}

.finding-main p,
.finding-main small {
  overflow-wrap: anywhere;
  word-break: break-word;
  line-height: 1.6;
}

.finding-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.finding-context span {
  max-width: 100%;
  padding: 4px 8px;
  border: 1px solid rgba(var(--oat-primary-rgb), .16);
  border-radius: 999px;
  background: rgba(var(--oat-primary-rgb), .05);
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.finding-actions {
  justify-content: flex-start;
}

.finding-actions button {
  flex: 0 0 auto;
}

.writeback-form {
  display: grid;
  grid-column: 1 / -1;
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(var(--oat-primary-rgb), .18);
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(var(--oat-primary-rgb), .06), rgba(255, 255, 255, .92));
}

.writeback-intro {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-text);
}

.writeback-intro span {
  color: var(--oat-text-muted);
  font-size: 13px;
}

.writeback-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.evidence-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.writeback-message {
  overflow: auto;
  max-height: 360px;
  margin: 10px 0 0;
  padding: 12px;
  border: 1px solid var(--oat-border);
  border-radius: 12px;
  background: #0f172a;
  color: #e2e8f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.notice.danger {
  border-color: rgba(220, 38, 38, .25);
  color: var(--oat-danger);
  background: rgba(220, 38, 38, .06);
}

.empty-state {
  display: grid;
  gap: 5px;
  min-height: 180px;
  place-content: center;
  text-align: center;
}

.empty-state.compact {
  min-height: 90px;
}

@media (max-width: 1100px) {
  .two-column,
  .workspace-tabs {
    grid-template-columns: 1fr;
  }

  .metrics-strip,
  .table-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .verification-header,
  .gate-band,
  .finding-card,
  .result-toolbar {
    align-items: stretch;
    flex-direction: column;
    grid-template-columns: 1fr;
  }

  .metrics-strip,
  .inline-grid,
  .table-row {
    grid-template-columns: 1fr;
  }
}
</style>
