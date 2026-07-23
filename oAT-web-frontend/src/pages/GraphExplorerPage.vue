<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 事实图谱</div>
        <h1>图谱工作台</h1>
        <p class="subtext">投影静态/动态/融合事实图，查看融合三态、断言一致性、预聚合读模型，并执行增量与失效传播。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadGraph" />
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <div class="projection-guide">
      <strong>投影事实图</strong>
      <span>将当前基线中的源码、覆盖率、测试执行和追溯资料转换成可查询的节点与关系；不会重新运行 AI 分析。</span>
      <span>建议首次使用按“静态图 → 控制流 / 依赖 → 覆盖率 / 分支 / 测试执行 → 追溯图”构建。</span>
    </div>
    <div class="toolbar">
      <button v-for="p in projections" :key="p.key" type="button" class="chip-button"
              :title="p.hint" :disabled="busyKey === p.key" @click="runProjection(p.key)">
        {{ busyKey === p.key ? '投影中...' : p.label }}
      </button>
    </div>

    <!-- Summary strip -->
    <div v-if="graph" class="summary-strip">
      <span class="summary-chip" :class="{ on: graph.summary.staticReady }">静态 {{ graph.summary.staticReady ? '就绪' : '缺失' }}</span>
      <span class="summary-chip" :class="{ on: graph.summary.runtimeReady }">动态 {{ graph.summary.runtimeReady ? '就绪' : '缺失' }}</span>
      <span class="summary-chip" :class="{ on: graph.summary.traceabilityReady }">追溯 {{ graph.summary.traceabilityReady ? '就绪' : '缺失' }}</span>
      <span class="summary-state">融合状态：{{ fusionStateText(graph.summary.fusionState) }}</span>
    </div>

    <!-- Tabs -->
    <nav class="tabs">
      <button v-for="t in tabs" :key="t.key" type="button" class="tab" :class="{ active: activeTab === t.key }"
              @click="activeTab = t.key">{{ t.label }}</button>
    </nav>

    <!-- GRAPH QUERY TAB -->
    <div v-show="activeTab === 'graph'" class="tab-panel">
      <p class="feature-intro"><strong>图谱查询：</strong>查看当前范围内的事实节点与关系。数据来自当前基线的活跃图谱节点和边；未聚焦时按节点类型和名称取前 {{ GRAPH_QUERY_MAX_NODES }} 个节点，聚焦时按节点 ID 做深度遍历。表格中的“测试执行”表示测试报告或流水线中的一次测试运行记录，不是源码方法。</p>
      <div class="query-bar">
        <label class="field-inline focus-field">
          <span>聚焦节点 ID</span>
          <span class="focus-input-wrap">
            <input v-model.trim="focusId" type="text" list="graph-focus-node-options" placeholder="留空=全量；可从表格点“聚焦”" />
            <button v-if="focusId" type="button" aria-label="清空聚焦节点 ID" title="清空" @click="focusId = ''">×</button>
          </span>
          <datalist id="graph-focus-node-options">
            <option v-for="node in graph?.nodes || []" :key="node.id" :value="node.id">
              {{ nodeKindText(node.kind) }} · {{ node.displayName }}
            </option>
          </datalist>
        </label>
        <label class="field-inline">
          <span>深度</span>
          <input v-model.number="depth" type="number" min="1" max="6" />
        </label>
        <label class="field-inline">
          <span>节点上限</span>
          <input v-model.number="maxNodes" type="number" min="1" :max="GRAPH_QUERY_MAX_NODES" />
        </label>
        <label class="field-inline">
          <span>边上限</span>
          <input v-model.number="maxEdges" type="number" min="1" :max="GRAPH_QUERY_MAX_EDGES" />
        </label>
        <button type="button" class="primary-button" :disabled="loading" @click="loadGraph">查询</button>
      </div>

      <div v-if="graph" class="graph-result">
        <div v-if="graph.clipReasons.length" class="clip-banner">
          <strong>裁剪提示</strong>
          <ul><li v-for="(r, i) in graph.clipReasons" :key="i">{{ r }}</li></ul>
          <small v-if="graph.expandHint">{{ graph.expandHint }}</small>
        </div>
        <div class="graph-stats">
          <span>业务节点 {{ visibleGraphNodes.length }}<template v-if="externalFilteredNodeCount"> / 已过滤框架 {{ externalFilteredNodeCount }}</template></span>
          <span>业务边 {{ visibleGraphEdges.length }}<template v-if="graph.edgesInScope"> / 范围内 {{ graph.edgesInScope }}</template></span>
          <span>深度 {{ graph.depth }}</span>
        </div>
        <div class="node-kind-legend">
          <span v-for="(count, kind) in nodeKindCounts" :key="kind" class="kind-chip" :class="`k-${kind}`">
            {{ nodeKindText(kind) }} {{ count }}
          </span>
          <span class="view-toggle">
            <button type="button" :class="{ active: graphViewMode === 'graph' }" @click="graphViewMode = 'graph'">图形</button>
            <button type="button" :class="{ active: graphViewMode === 'table' }" @click="graphViewMode = 'table'">表格</button>
          </span>
        </div>

        <div
          v-if="graphViewMode === 'graph'"
          ref="graphScrollRef"
          class="svg-canvas-wrap"
          :class="{ panning: graphPanning }"
          @scroll="updateGraphOverviewViewport"
          @pointerdown="startGraphPan"
          @pointermove="moveGraphPan"
          @pointerup="endGraphPan"
          @pointercancel="endGraphPan"
        >
          <div class="graph-explainer"><strong>阅读方式：</strong>圆点代表事实节点，连线代表它们之间的关系；颜色区分节点类型。图按“需求与测试 → 代码结构 → 控制流与运行证据”分层排列，便于观察证据如何落到代码。左上角概览和主图均绘制本次查询返回的全部节点；可横向、纵向滚动浏览，悬停节点可查看详情。</div>
          <div
            v-if="svgLayout.nodes.length"
            ref="graphVisualsRef"
            class="graph-visuals"
            :style="{ width: `${svgLayout.width}px`, minHeight: `${svgLayout.height + 128}px` }"
          >
            <svg
              class="graph-overview"
              :viewBox="`0 0 ${svgLayout.width} ${svgLayout.height}`"
              aria-label="图谱概览"
              @pointerdown.prevent="startOverviewPan"
              @pointermove.prevent="moveOverviewPan"
              @pointerup="endOverviewPan"
              @pointercancel="endOverviewPan"
            >
              <line v-for="(e, i) in svgLayout.edges" :key="`mini-e${i}`" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" class="mini-edge" />
              <circle v-for="n in svgLayout.nodes" :key="`mini-n${n.id}`" :cx="n.x" :cy="n.y" r="3" class="svg-node" :class="`k-${n.kind}`" />
              <rect
                v-if="overviewViewport.visible"
                class="overview-viewport"
                :x="overviewViewport.x"
                :y="overviewViewport.y"
                :width="overviewViewport.width"
                :height="overviewViewport.height"
                rx="10"
              />
            </svg>
            <svg
              :viewBox="`0 0 ${svgLayout.width} ${svgLayout.height}`"
              class="svg-canvas"
              preserveAspectRatio="xMidYMid meet"
              :style="{ width: `${svgLayout.width}px`, height: `${svgLayout.height}px` }"
            >
              <line v-for="(e, i) in svgLayout.edges" :key="`e${i}`"
                    :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" class="svg-edge" :class="`et-${e.type}`" />
              <g v-for="n in svgLayout.nodes" :key="n.id" :transform="`translate(${n.x},${n.y})`" class="svg-node-g"
                 @mouseenter="showNodeTooltip(n.id, $event)" @mousemove="moveNodeTooltip" @mouseleave="hideNodeTooltip">
                <circle :r="hoverNode === n.id ? 10 : 6" class="svg-node" :class="`k-${n.kind}`" />
                <text v-if="hoverNode === n.id || svgLayout.nodes.length <= 40" :y="-12" class="svg-label">{{ n.label }}</text>
              </g>
            </svg>
          </div>
          <div
            v-if="hoverGraphNode && hoverSvgNode"
            class="svg-node-tooltip"
            :class="{ 'near-right': tooltipPosition.nearRight, 'near-bottom': tooltipPosition.nearBottom }"
            :style="{ left: `${tooltipPosition.x}px`, top: `${tooltipPosition.y}px` }"
            role="tooltip"
          >
            <strong>{{ hoverGraphNode.displayName || hoverGraphNode.id }}</strong>
            <dl>
              <template v-for="item in nodeTooltipRows(hoverGraphNode)" :key="item.label">
                <dt>{{ item.label }}</dt>
                <dd>{{ item.value }}</dd>
              </template>
            </dl>
          </div>
          <p v-if="!svgLayout.nodes.length" class="table-note">当前范围没有可绘制的边关系，请调整聚焦符号或深度。</p>
        </div>

        <template v-else>
          <div class="list-toolbar">
            <input v-model.trim="nodeTableKeyword" type="search" placeholder="搜索类型、名称、源码位置、用例或执行环境" @input="nodeTablePage = 1" />
            <span>共 {{ filteredGraphNodes.length }} 个节点</span>
          </div>
          <div class="result-table-block">
            <div class="table-scroll">
              <table class="data-table graph-node-table">
                <thead><tr><th>类型</th><th>名称</th><th>说明</th><th>来源 / 执行信息</th><th>关联关系</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="n in pagedGraphNodes" :key="n.id">
                    <td><span class="kind-tag" :class="`k-${n.kind}`">{{ nodeKindText(n.kind) }}</span></td>
                    <td class="node-name">{{ graphNodeDisplayName(n) }}</td>
                    <td>{{ graphNodeDescription(n) }}</td>
                    <td class="muted">{{ graphNodeSourceInfo(n) }}</td>
                    <td>{{ graphNodeRelationSummary(n) }}</td>
                    <td><button type="button" class="inline-table-button" :disabled="loading" @click="focusGraphNode(n.id)">查看关联</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <AppPagination
            v-if="filteredGraphNodes.length"
            v-model:page="nodeTablePage"
            v-model:page-size="nodeTablePageSize"
            :total="filteredGraphNodes.length"
            item-name="个节点"
            :page-sizes="[10, 20, 50]"
          />
          <p v-if="!filteredGraphNodes.length" class="table-note">没有匹配的节点。</p>
        </template>
      </div>
      <div v-else-if="!loading" class="empty-state"><strong>暂无图谱数据</strong><span>请先投影事实图，再执行查询。</span></div>
    </div>

    <!-- FUSION TAB -->
    <div v-show="activeTab === 'fusion'" class="tab-panel">
      <p class="feature-intro"><strong>融合三态：</strong>数据来自当前基线的活跃 METHOD 节点。后端把方法节点与动态证据和静态调用关系关联：有覆盖/运行调用/触达证据为“已执行确认”，只有静态调用关系为“可达未执行”，两者都没有为“不可观测”。</p>
      <button type="button" class="secondary-button" :disabled="fusionLoading" @click="loadFusion">
        {{ fusionLoading ? '加载中...' : '加载融合三态' }}
      </button>
      <div v-if="fusion" class="fusion-result">
        <div class="fusion-summary">
          <div class="fusion-card confirmed"><span class="num">{{ fusion.executedConfirmed }}</span><span>已执行确认</span></div>
          <div class="fusion-card reachable"><span class="num">{{ fusion.reachableNotExecuted }}</span><span>可达未执行</span></div>
          <div class="fusion-card observable"><span class="num">{{ fusion.notObservable }}</span><span>不可观测</span></div>
          <div class="fusion-card total"><span class="num">{{ fusion.totalMethods }}</span><span>方法总数</span></div>
        </div>
        <div v-if="fusion.clipped" class="notice warn">{{ fusion.clipReason }}</div>
        <div v-if="fusion.totalMethods === 0" class="empty-state"><strong>还没有可融合的方法数据</strong><span>请先投影静态图；如需区分“已执行确认”和“可达未执行”，还需投影覆盖率或测试执行数据。</span></div>
        <div v-else class="result-table-block">
          <div class="list-toolbar">
            <input v-model.trim="fusionKeyword" type="search" placeholder="搜索方法、符号或源码位置" @input="fusionNodePage = 1" />
            <select v-model="fusionStateFilter" class="policy-select compact-select">
              <option value="">全部状态</option>
              <option value="EXECUTED_CONFIRMED">已执行确认</option>
              <option value="REACHABLE_NOT_EXECUTED">可达未执行</option>
              <option value="NOT_OBSERVABLE">不可观测</option>
            </select>
          </div>
          <div class="result-table-head">
            <strong>方法融合状态</strong>
            <span>显示 {{ pagedFusionNodes.length }} / {{ filteredFusionNodes.length }} 条</span>
          </div>
          <div class="table-scroll">
            <table class="data-table fusion-table">
              <thead><tr><th>状态</th><th>方法</th><th>符号</th></tr></thead>
              <tbody>
                <tr v-for="n in pagedFusionNodes" :key="n.nodeId">
                  <td><span class="fusion-tag" :class="n.fusionState.toLowerCase()">{{ fusionNodeText(n.fusionState) }}</span></td>
                  <td class="mono">{{ n.displayName }}</td>
                  <td class="mono muted">{{ n.stableSymbolId || n.locator || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <AppPagination v-model:page="fusionNodePage" v-model:page-size="fusionNodePageSize" :total="filteredFusionNodes.length" item-name="个方法" :page-sizes="[10, 20, 50, 100]" />
          <p v-if="!filteredFusionNodes.length" class="table-note">没有匹配的方法。</p>
        </div>
      </div>
    </div>

    <!-- ASSERTION TAB -->
    <div v-show="activeTab === 'assertion'" class="tab-panel">
      <p class="feature-intro"><strong>断言一致性：</strong>检查测试用例的断言描述是否覆盖对应验收标准（AC）的关键约束，用于发现“测试运行通过但没有验证需求”的风险。</p>
      <div class="toolbar">
        <button type="button" class="secondary-button" :disabled="assertionLoading" @click="loadAssertion">刷新</button>
        <button type="button" class="primary-button" :disabled="assertionLoading" @click="runAssertion">重新校验</button>
      </div>
      <div v-if="assertions.length" class="result-table-block">
        <div class="list-toolbar">
          <input v-model.trim="assertionKeyword" type="search" placeholder="搜索 AC、最佳用例或结论" @input="assertionPage = 1" />
          <select v-model="assertionVerdictFilter" class="policy-select compact-select">
            <option value="">全部结论</option>
            <option value="ASSERTION_ALIGNED">断言对齐</option>
            <option value="SUSPECTED_FALSE_PASS">疑似假通过</option>
          </select>
        </div>
        <div class="result-table-head">
          <strong>断言一致性结果</strong>
          <span>显示 {{ pagedAssertions.length }} / {{ filteredAssertions.length }} 条</span>
        </div>
        <div class="table-scroll">
          <table class="data-table assertion-table">
            <thead><tr><th>AC</th><th>结论</th><th>断言重合度</th><th>最佳用例</th></tr></thead>
            <tbody>
              <tr v-for="a in pagedAssertions" :key="a.criterionId">
                <td class="mono">{{ a.acKey }}</td>
                <td><span class="verdict-chip" :class="a.verdict === 'SUSPECTED_FALSE_PASS' ? 'failed' : 'passed'">{{ assertionVerdictText(a.verdict) }}</span></td>
                <td>{{ pct(a.assertionOverlap) }}</td>
                <td class="mono muted">{{ a.bestTestcaseKey || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <AppPagination v-model:page="assertionPage" v-model:page-size="assertionPageSize" :total="filteredAssertions.length" item-name="条 AC 结果" :page-sizes="[10, 20, 50, 100]" />
        <p v-if="!filteredAssertions.length" class="table-note">没有匹配的断言一致性结果。</p>
      </div>
      <div v-else-if="!assertionLoading" class="empty-state"><strong>暂无断言一致性结果</strong><span>需要先运行 AI 分析，生成验收标准、测试用例及其追溯关系；然后点击“重新校验”。</span></div>
    </div>

    <!-- READ MODEL TAB -->
    <div v-show="activeTab === 'readModel'" class="tab-panel">
      <p class="feature-intro"><strong>读模型：</strong>数据来自当前基线的活跃预聚合结果。点击“重建读模型”会重新从验收标准、追溯链接、运行边和融合三态计算；展示页只读取聚合表，不新增事实。</p>
      <div class="toolbar">
        <button type="button" class="primary-button" :disabled="readModelBusy" @click="doRebuildReadModels">重建读模型</button>
        <select v-model="readModelKind" class="policy-select" @change="loadReadModel">
          <option value="RM_AC_COVERAGE_SUMMARY">AC 覆盖汇总</option>
          <option value="RM_SYMBOL_TEST_PROTECTION">符号测试保护</option>
          <option value="RM_HOT_CALL_CHAIN">热点调用链</option>
          <option value="RM_UNCOVERED_UNITS">未覆盖单元</option>
          <option value="RM_IMPACT_SUMMARY">影响面汇总</option>
          <option value="RM_QUALITY_GATE_SUMMARY">门禁指标汇总</option>
        </select>
      </div>
      <div v-if="readModelRows.length" class="read-model-result">
        <p class="read-model-caption">{{ readModelDescription }}</p>
        <p class="data-source-note">{{ readModelSourceNote }}</p>
        <div class="result-table-block">
          <div class="list-toolbar">
            <input v-model.trim="readModelKeyword" type="search" placeholder="搜索当前读模型结果" @input="readModelPage = 1" />
          </div>
          <div class="result-table-head">
            <strong>{{ readModelKindText }}</strong>
            <span>显示 {{ pagedReadModelDisplayRows.length }} / {{ filteredReadModelDisplayRows.length }} 条</span>
          </div>
          <div class="table-scroll">
            <table class="data-table read-model-table">
              <thead><tr><th v-for="column in readModelColumns" :key="column.key">{{ column.label }}</th></tr></thead>
              <tbody>
                <tr v-for="row in pagedReadModelDisplayRows" :key="row.id">
                  <td v-for="cell in row.cells" :key="cell.key" :class="{ mono: cell.mono, muted: cell.muted }">{{ cell.value }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <AppPagination v-model:page="readModelPage" v-model:page-size="readModelPageSize" :total="filteredReadModelDisplayRows.length" item-name="条读模型" :page-sizes="[10, 20, 50, 100]" />
          <p v-if="!filteredReadModelDisplayRows.length" class="table-note">没有匹配的读模型结果。</p>
        </div>
      </div>
      <div v-else-if="!readModelBusy" class="empty-state"><strong>暂无读模型数据</strong><span>请先完成相关事实图投影；如果需要 AC 覆盖或门禁类汇总，还需先运行 AI 分析，然后点击“重建读模型”。</span></div>
    </div>

    <!-- BASELINE COMPARISON TAB -->
    <div v-show="activeTab === 'compare'" class="tab-panel">
      <p class="feature-intro"><strong>基线比较：</strong>以当前基线为“新版本”，选择一个历史基线作为“旧版本”，查看哪些验收标准的证据闭合和哪些方法的执行状态发生变化。</p>
      <section class="ops-block">
        <h3 class="section-title">新旧基线比较</h3>
        <p class="ops-hint">与另一个基线对比：哪些 AC 的证据闭合发生增减，哪些方法的融合三态发生迁移。当前基线作为目标（新），选择一个基准（旧）。</p>
        <label class="field-inline">
          <span>历史基线（旧）</span>
          <select v-model="compareBaseId" class="policy-select">
            <option value="">请选择要对比的历史基线</option>
            <option v-for="item in comparableBaselines" :key="item.id" :value="item.id">{{ item.name }} · {{ formatBaselineTime(item.createTime) }}</option>
          </select>
        </label>
        <button type="button" class="primary-button" :disabled="compareBusy || !compareBaseId" @click="doCompare">执行比较</button>
      </section>

      <template v-if="comparison">
        <section class="comparison-summary">
          <article><span>验收标准证据变化</span><strong>{{ comparison.acDeltas.length }}</strong><small>新增、移除或证据变化</small></article>
          <article><span>融合状态迁移</span><strong>{{ comparison.fusionDeltas.length }}</strong><small>方法执行状态发生变化</small></article>
          <article><span>当前版本</span><strong>{{ currentBaselineLabel }}</strong><small>与所选历史基线对比</small></article>
        </section>
        <section class="ops-block comparison-card">
          <div class="comparison-tabs" role="tablist" aria-label="基线比较结果类型">
            <button type="button" :class="{ active: comparisonTab === 'ac' }" @click="comparisonTab = 'ac'">AC 证据变化 <span>{{ filteredAcDeltas.length }}</span></button>
            <button type="button" :class="{ active: comparisonTab === 'fusion' }" @click="comparisonTab = 'fusion'">融合状态迁移 <span>{{ filteredFusionDeltas.length }}</span></button>
          </div>

          <template v-if="comparisonTab === 'ac'">
            <div ref="acComparisonRef" class="comparison-card-head">
              <h3 class="section-title">AC 证据变化 <span class="badge-count">{{ filteredAcDeltas.length }}</span></h3>
              <input v-model.trim="acDeltaKeyword" class="compact-search" type="search" placeholder="搜索 AC 或证据" @input="acDeltaPage = 1" />
              <select v-model="acDeltaFilter" class="policy-select compact-select">
                <option value="">全部变化</option><option value="ADDED">仅新增</option><option value="REMOVED">仅移除</option><option value="EVIDENCE_CHANGED">仅证据变化</option>
              </select>
            </div>
            <div v-if="filteredAcDeltas.length" class="comparison-table-wrap">
              <table class="data-table">
                <thead><tr><th>验收标准</th><th>变化</th><th>旧证据</th><th>新证据</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="d in pagedAcDeltas" :key="d.criterionId">
                    <td class="mono">{{ d.acKey }}</td>
                    <td><span class="verdict-chip" :class="acDeltaClass(d.changeType)">{{ acDeltaText(d.changeType) }}</span></td>
                    <td class="mono muted">{{ evidenceCodeText(d.beforeEvidence) }}</td>
                    <td class="mono">{{ evidenceCodeText(d.afterEvidence) }}</td>
                    <td><button type="button" class="inline-table-button" @click="selectedAcDeltaId = selectedAcDeltaId === d.criterionId ? '' : d.criterionId">详情</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="table-note">当前筛选下没有 AC 证据变化。</p>
            <AppPagination v-if="filteredAcDeltas.length" v-model:page="acDeltaPage" v-model:page-size="acDeltaPageSize" :total="filteredAcDeltas.length" item-name="条 AC 变化" :page-sizes="[10, 20, 50]" @update:page="scrollToComparison(acComparisonRef)" />
            <section v-if="selectedAcDelta" class="comparison-detail">
              <h4>{{ selectedAcDelta.acKey }} · {{ acDeltaText(selectedAcDelta.changeType) }}</h4>
              <dl>
                <dt>验收标准 ID</dt><dd class="mono">{{ selectedAcDelta.criterionId }}</dd>
                <dt>变化类型</dt><dd>{{ acDeltaText(selectedAcDelta.changeType) }}</dd>
                <dt>旧证据</dt><dd>{{ evidenceCodeText(selectedAcDelta.beforeEvidence) }} <code>{{ selectedAcDelta.beforeEvidence || '-' }}</code></dd>
                <dt>新证据</dt><dd>{{ evidenceCodeText(selectedAcDelta.afterEvidence) }} <code>{{ selectedAcDelta.afterEvidence || '-' }}</code></dd>
              </dl>
            </section>
            <small class="ops-hint">证据：用例、实现、覆盖率、执行；“无”表示该类依据缺失。</small>
          </template>

          <template v-else>
            <div ref="fusionComparisonRef" class="comparison-card-head">
              <h3 class="section-title">融合状态迁移 <span class="badge-count">{{ filteredFusionDeltas.length }}</span></h3>
              <input v-model.trim="fusionDeltaKeyword" class="compact-search" type="search" placeholder="搜索代码符号或状态" @input="fusionDeltaPage = 1" />
              <select v-model="fusionDeltaFilter" class="policy-select compact-select">
                <option value="">全部迁移</option><option value="EXECUTED_CONFIRMED">迁移为已执行确认</option><option value="REACHABLE_NOT_EXECUTED">迁移为可达未执行</option><option value="NOT_OBSERVABLE">迁移为不可观测</option>
              </select>
            </div>
            <div v-if="filteredFusionDeltas.length" class="comparison-table-wrap">
              <table class="data-table">
                <thead><tr><th>代码符号</th><th>旧状态</th><th>新状态</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="(d, i) in pagedFusionDeltas" :key="fusionDeltaKey(d, i)">
                    <td class="mono">{{ d.symbol }}</td>
                    <td><span class="fusion-tag" :class="d.beforeState.toLowerCase()">{{ fusionNodeText(d.beforeState) }}</span></td>
                    <td><span class="fusion-tag" :class="d.afterState.toLowerCase()">{{ fusionNodeText(d.afterState) }}</span></td>
                    <td><button type="button" class="inline-table-button" @click="selectedFusionDeltaKey = selectedFusionDeltaKey === fusionDeltaKey(d, i) ? '' : fusionDeltaKey(d, i)">详情</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="table-note">当前筛选下没有融合状态迁移。</p>
            <AppPagination v-if="filteredFusionDeltas.length" v-model:page="fusionDeltaPage" v-model:page-size="fusionDeltaPageSize" :total="filteredFusionDeltas.length" item-name="条状态迁移" :page-sizes="[10, 20, 50]" @update:page="scrollToComparison(fusionComparisonRef)" />
            <section v-if="selectedFusionDelta" class="comparison-detail">
              <h4>{{ selectedFusionDelta.symbol }}</h4>
              <dl>
                <dt>代码符号</dt><dd class="mono">{{ selectedFusionDelta.symbol }}</dd>
                <dt>旧状态</dt><dd>{{ fusionNodeText(selectedFusionDelta.beforeState) }} <code>{{ selectedFusionDelta.beforeState }}</code></dd>
                <dt>新状态</dt><dd>{{ fusionNodeText(selectedFusionDelta.afterState) }} <code>{{ selectedFusionDelta.afterState }}</code></dd>
              </dl>
            </section>
          </template>
        </section>
      </template>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchVerificationOverview, type VerificationBaseline } from '@/api/verification'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import AppPagination from '@/components/AppPagination.vue'
import { useToast } from '@/composables/useToast'
import { isBusinessCodeItem, isGraphCodeKind } from '@/shared/codeGraphScope'
import {
  compareBaselines, evaluateAssertionConsistency, fetchAssertionConsistency,
  fetchFusionView, fetchGraphView, fetchReadModel,
  projectBranchCoverageGraph, projectControlFlowGraph, projectRuntimeCoverageGraph,
  projectStaticDependencyGraph, projectStaticGraph, projectTestExecutionGraph,
  projectTraceabilityGraph, rebuildReadModels,
  type AssertionConsistencyResult, type BaselineComparisonResult, type FusionStateDelta,
  type FusionView, type GraphAggregate, type GraphNode, type GraphView, type ReadModelKind,
} from '@/api/graph'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))

const tabs = [
  { key: 'graph', label: '图谱查询' },
  { key: 'fusion', label: '融合三态' },
  { key: 'assertion', label: '断言一致性' },
  { key: 'readModel', label: '读模型' },
  { key: 'compare', label: '基线比较' },
] as const
const activeTab = ref<(typeof tabs)[number]['key']>('graph')

const projections = [
  { key: 'static', label: '静态图', hint: '从源码索引生成文件、类型、方法和静态调用关系。' },
  { key: 'cfg', label: '控制流图', hint: '生成方法内部的基本块、条件分支和控制流关系。' },
  { key: 'dependency', label: '依赖图', hint: '生成继承、注入、读写、路由和依赖关系。' },
  { key: 'coverage', label: '覆盖率', hint: '将导入的覆盖率报告关联到代码单元。' },
  { key: 'branch', label: '分支覆盖', hint: '将分支覆盖报告关联到控制流分支。' },
  { key: 'testExec', label: '测试执行', hint: '将测试执行记录关联到测试用例和运行证据。' },
  { key: 'traceability', label: '追溯图', hint: '将已生成的 AC、用例、实现和运行证据串成追溯关系。' },
] as const
type ProjectionKey = (typeof projections)[number]['key']
const GRAPH_QUERY_MAX_NODES = 1000
const GRAPH_QUERY_MAX_EDGES = 2000
const FUSION_VIEW_MAX_NODES = 5000

const loading = ref(false)
const error = ref('')
const busyKey = ref('')

const graph = ref<GraphView | null>(null)
const focusId = ref('')
const depth = ref(3)
const maxNodes = ref(500)
const maxEdges = ref(1000)

const fusion = ref<FusionView | null>(null)
const fusionLoading = ref(false)

const assertions = ref<AssertionConsistencyResult[]>([])
const assertionLoading = ref(false)

const readModelKind = ref<ReadModelKind>('RM_AC_COVERAGE_SUMMARY')
const readModelRows = ref<GraphAggregate[]>([])
const readModelBusy = ref(false)

const graphViewMode = ref<'graph' | 'table'>('graph')
const hoverNode = ref('')
const nodeTableKeyword = ref('')
const nodeTablePage = ref(1)
const nodeTablePageSize = ref(10)
const fusionKeyword = ref('')
const fusionStateFilter = ref('')
const fusionNodePage = ref(1)
const fusionNodePageSize = ref(20)
const assertionKeyword = ref('')
const assertionVerdictFilter = ref('')
const assertionPage = ref(1)
const assertionPageSize = ref(20)
const readModelKeyword = ref('')
const readModelPage = ref(1)
const readModelPageSize = ref(20)
const graphScrollRef = ref<HTMLElement | null>(null)
const graphVisualsRef = ref<HTMLElement | null>(null)
const overviewDragging = ref(false)
const graphPanning = ref(false)
const graphPanStart = ref({ x: 0, y: 0, left: 0, top: 0 })
const tooltipPosition = ref({ x: 0, y: 0, nearRight: false, nearBottom: false })
const graphScrollState = ref({
  left: 0,
  top: 0,
  width: 1,
  height: 1,
  scrollWidth: 1,
  scrollHeight: 1,
  visualLeft: 0,
  visualTop: 0,
  visualWidth: 1,
  visualHeight: 1,
})

const compareBaseId = ref('')
const baselines = ref<VerificationBaseline[]>([])
const comparison = ref<BaselineComparisonResult | null>(null)
const compareBusy = ref(false)
const comparisonTab = ref<'ac' | 'fusion'>('ac')
const selectedAcDeltaId = ref('')
const selectedFusionDeltaKey = ref('')
const acDeltaKeyword = ref('')
const fusionDeltaKeyword = ref('')
const acDeltaFilter = ref('')
const fusionDeltaFilter = ref('')
const acDeltaPage = ref(1)
const acDeltaPageSize = ref(10)
const fusionDeltaPage = ref(1)
const fusionDeltaPageSize = ref(10)
const acComparisonRef = ref<HTMLElement | null>(null)
const fusionComparisonRef = ref<HTMLElement | null>(null)

const comparableBaselines = computed(() => baselines.value.filter((item) => item.id !== baselineId.value))
const currentBaselineLabel = computed(() => baselines.value.find((item) => item.id === baselineId.value)?.name || '当前基线')
const filteredAcDeltas = computed(() => (comparison.value?.acDeltas || []).filter((delta) => {
  if (acDeltaFilter.value && delta.changeType !== acDeltaFilter.value) return false
  const keyword = acDeltaKeyword.value.trim().toLowerCase()
  if (!keyword) return true
  return [delta.criterionId, delta.acKey, delta.changeType, delta.beforeEvidence, delta.afterEvidence, acDeltaText(delta.changeType), evidenceCodeText(delta.beforeEvidence), evidenceCodeText(delta.afterEvidence)]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword)
}))
const filteredFusionDeltas = computed(() => (comparison.value?.fusionDeltas || []).filter((delta) => {
  if (!isBusinessCodeItem({ symbol: delta.symbol, displayName: delta.symbol })) return false
  if (fusionDeltaFilter.value && delta.afterState !== fusionDeltaFilter.value) return false
  const keyword = fusionDeltaKeyword.value.trim().toLowerCase()
  if (!keyword) return true
  return [delta.symbol, delta.beforeState, delta.afterState, fusionNodeText(delta.beforeState), fusionNodeText(delta.afterState)]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword)
}))
const pagedAcDeltas = computed(() => pageSlice(filteredAcDeltas.value, acDeltaPage.value, acDeltaPageSize.value))
const pagedFusionDeltas = computed(() => pageSlice(filteredFusionDeltas.value, fusionDeltaPage.value, fusionDeltaPageSize.value))
const selectedAcDelta = computed(() => filteredAcDeltas.value.find((delta) => delta.criterionId === selectedAcDeltaId.value) || null)
const selectedFusionDelta = computed(() => filteredFusionDeltas.value.find((delta, index) => fusionDeltaKey(delta, index) === selectedFusionDeltaKey.value) || null)

const readModelDescription = computed(() => ({
  RM_AC_COVERAGE_SUMMARY: '按验收标准汇总已关联的用例、实现、覆盖率和执行证据，用于发现验证闭环缺口。',
  RM_SYMBOL_TEST_PROTECTION: '按代码符号汇总它关联的验收标准和测试用例，用于评估代码变更是否受到测试保护。',
  RM_HOT_CALL_CHAIN: '汇总运行期间观察到的调用链路和耗时，用于定位高频或高耗时路径。',
  RM_UNCOVERED_UNITS: '列出静态上可达、但当前基线未观察到动态执行证据的代码单元。',
  RM_IMPACT_SUMMARY: '按代码符号汇总可能受变更影响的验收标准和测试用例，用于辅助评估变更范围。',
  RM_QUALITY_GATE_SUMMARY: '汇总当前基线的需求验证闭环指标。验证闭环按“有用例依据 + 有实现依据 + 有覆盖率依据 + 有执行依据”同时满足计算。',
}[readModelKind.value] || '图谱事实的预聚合查询结果。'))
const readModelSourceNote = computed(() => ({
  RM_AC_COVERAGE_SUMMARY: '来源：验收标准列表 + 追溯链接。每一行对应 1 条验收标准，表头字段由该 AC 是否存在 TESTCASE、SOURCE_SYMBOL、COVERAGE、EXECUTION 链接计算。',
  RM_SYMBOL_TEST_PROTECTION: '来源：追溯链接。先找 SOURCE_SYMBOL 关联的 AC，再通过这些 AC 找 TESTCASE，用于判断代码符号是否有测试保护。',
  RM_HOT_CALL_CHAIN: '来源：活跃 CALLS_RUNTIME 边。调用次数、耗时和结果来自运行调用边的 attributes。',
  RM_UNCOVERED_UNITS: '来源：融合三态。只展示“可达未执行”的方法节点，即静态可达但没有动态执行证据。',
  RM_IMPACT_SUMMARY: '来源：追溯链接。按 SOURCE_SYMBOL 汇总可能影响的 AC 和建议复测用例。',
  RM_QUALITY_GATE_SUMMARY: '来源：验收标准列表 + 追溯链接。总数来自 AC 数量；用例/实现/覆盖率/执行分别统计有对应链接的 AC；验证闭环为四类依据都存在的 AC。',
}[readModelKind.value] || '来源：当前基线已重建的活跃读模型聚合。'))

type ReadModelColumn = { key: string; label: string }
type ReadModelCell = { key: string; value: string; mono?: boolean; muted?: boolean }
const readModelColumns = computed<ReadModelColumn[]>(() => ({
  RM_AC_COVERAGE_SUMMARY: [{ key: 'ac', label: '验收标准' }, { key: 'testcase', label: '测试用例' }, { key: 'implementation', label: '实现依据' }, { key: 'coverage', label: '覆盖率依据' }, { key: 'execution', label: '执行依据' }, { key: 'links', label: '关联依据数' }],
  RM_SYMBOL_TEST_PROTECTION: [{ key: 'symbol', label: '代码符号' }, { key: 'ac', label: '关联验收标准' }, { key: 'testcase', label: '关联测试用例' }, { key: 'protected', label: '测试保护' }],
  RM_HOT_CALL_CHAIN: [{ key: 'source', label: '调用起点' }, { key: 'target', label: '调用目标' }, { key: 'count', label: '调用次数' }, { key: 'duration', label: '耗时' }, { key: 'outcome', label: '结果' }],
  RM_UNCOVERED_UNITS: [{ key: 'unit', label: '未覆盖代码单元' }, { key: 'location', label: '位置' }, { key: 'reason', label: '原因' }],
  RM_IMPACT_SUMMARY: [{ key: 'symbol', label: '变更代码符号' }, { key: 'ac', label: '可能影响的验收标准' }, { key: 'testcase', label: '建议复测用例' }],
  RM_QUALITY_GATE_SUMMARY: [{ key: 'criteria', label: '验收标准总数' }, { key: 'testcase', label: '有用例依据' }, { key: 'implementation', label: '有实现依据' }, { key: 'coverage', label: '有覆盖率依据' }, { key: 'execution', label: '有执行依据' }, { key: 'closed', label: '全链路闭环' }],
}[readModelKind.value] || []))
const readModelDisplayRows = computed(() => readModelRows.value.map((row) => ({ id: row.id, cells: readModelCells(row.payload) })))
const filteredFusionNodes = computed(() => (fusion.value?.nodes || []).filter((node) => {
  if (!isBusinessCodeItem({ id: node.nodeId, displayName: node.displayName, stableSymbolId: node.stableSymbolId, locator: node.locator })) return false
  if (fusionStateFilter.value && node.fusionState !== fusionStateFilter.value) return false
  const keyword = fusionKeyword.value.trim().toLowerCase()
  if (!keyword) return true
  return [node.nodeId, node.displayName, node.stableSymbolId, node.locator, fusionNodeText(node.fusionState)]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword)
}))
const filteredAssertions = computed(() => assertions.value.filter((item) => {
  if (assertionVerdictFilter.value && item.verdict !== assertionVerdictFilter.value) return false
  const keyword = assertionKeyword.value.trim().toLowerCase()
  if (!keyword) return true
  return [item.criterionId, item.acKey, item.verdict, assertionVerdictText(item.verdict), item.bestTestcaseKey, pct(item.assertionOverlap)]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword)
}))
const filteredReadModelDisplayRows = computed(() => {
  const keyword = readModelKeyword.value.trim().toLowerCase()
  const rows = readModelDisplayRows.value.filter(isBusinessReadModelRow)
  if (!keyword) return rows
  return rows.filter((row) => [row.id, ...row.cells.map((cell) => cell.value)].join(' ').toLowerCase().includes(keyword))
})
const pagedFusionNodes = computed(() => pageSlice(filteredFusionNodes.value, fusionNodePage.value, fusionNodePageSize.value))
const pagedAssertions = computed(() => pageSlice(filteredAssertions.value, assertionPage.value, assertionPageSize.value))
const pagedReadModelDisplayRows = computed(() => pageSlice(filteredReadModelDisplayRows.value, readModelPage.value, readModelPageSize.value))
const readModelKindText = computed(() => ({
  RM_AC_COVERAGE_SUMMARY: 'AC 覆盖汇总',
  RM_SYMBOL_TEST_PROTECTION: '符号测试保护',
  RM_HOT_CALL_CHAIN: '热点调用链',
  RM_UNCOVERED_UNITS: '未覆盖单元',
  RM_IMPACT_SUMMARY: '影响面汇总',
  RM_QUALITY_GATE_SUMMARY: '门禁指标汇总',
}[readModelKind.value] || '读模型结果'))

const nodeKindCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const n of visibleGraphNodes.value) counts[n.kind] = (counts[n.kind] ?? 0) + 1
  return counts
})
const visibleGraphNodes = computed(() => (graph.value?.nodes || []).filter(isVisibleGraphNode))
const visibleGraphNodeIds = computed(() => new Set(visibleGraphNodes.value.map((node) => node.id)))
const visibleGraphEdges = computed(() => (graph.value?.edges || []).filter((edge) => visibleGraphNodeIds.value.has(edge.sourceNodeId) && visibleGraphNodeIds.value.has(edge.targetNodeId)))
const externalFilteredNodeCount = computed(() => Math.max(0, (graph.value?.nodes.length || 0) - visibleGraphNodes.value.length))
const graphNodeById = computed(() => new Map(visibleGraphNodes.value.map((node) => [node.id, node])))
const hoverGraphNode = computed(() => hoverNode.value ? graphNodeById.value.get(hoverNode.value) : undefined)
const hoverSvgNode = computed(() => svgLayout.value.nodes.find((node) => node.id === hoverNode.value))
const filteredGraphNodes = computed(() => {
  const keyword = nodeTableKeyword.value.trim().toLowerCase()
  const nodes = visibleGraphNodes.value
  if (!keyword) return nodes
  return nodes.filter((node) => graphNodeSearchText(node).includes(keyword))
})
const pagedGraphNodes = computed(() => {
  const page = clamp(nodeTablePage.value, 1, Math.max(1, Math.ceil(filteredGraphNodes.value.length / nodeTablePageSize.value)))
  const start = (page - 1) * nodeTablePageSize.value
  return filteredGraphNodes.value.slice(start, start + nodeTablePageSize.value)
})
const overviewViewport = computed(() => {
  const layout = svgLayout.value
  const state = graphScrollState.value
  if (!layout.nodes.length || state.visualWidth <= state.width && state.visualHeight <= state.height) {
    return { visible: false, x: 0, y: 0, width: 0, height: 0 }
  }
  const width = clamp((state.width / state.visualWidth) * layout.width, 24, layout.width)
  const height = clamp((state.height / state.visualHeight) * layout.height, 24, layout.height)
  const maxX = Math.max(0, layout.width - width)
  const maxY = Math.max(0, layout.height - height)
  const visualLeft = clamp(state.left - state.visualLeft, 0, Math.max(0, state.visualWidth - state.width))
  const visualTop = clamp(state.top - state.visualTop, 0, Math.max(0, state.visualHeight - state.height))
  return {
    visible: true,
    x: maxX ? (visualLeft / Math.max(1, state.visualWidth - state.width)) * maxX : 0,
    y: maxY ? (visualTop / Math.max(1, state.visualHeight - state.height)) * maxY : 0,
    width,
    height,
  }
})

interface SvgNode { id: string; label: string; kind: string; x: number; y: number }
interface SvgEdge { x1: number; y1: number; x2: number; y2: number; type: string }

const GRAPH_LANES: Array<{ label: string; kinds: string[] }> = [
  { label: '需求与测试', kinds: ['REQUIREMENT', 'ACCEPTANCE_CRITERION', 'TESTCASE', 'TEST_STEP', 'TEST_EXECUTION'] },
  { label: '代码结构', kinds: ['SOURCE_FILE', 'TYPE', 'METHOD', 'FIELD', 'ENDPOINT', 'CONFIG', 'SQL_STATEMENT'] },
  { label: '控制流与运行证据', kinds: ['BASIC_BLOCK', 'DECISION', 'BRANCH', 'RUNTIME_SPAN', 'COVERAGE_UNIT'] },
]

const svgLayout = computed<{ nodes: SvgNode[]; edges: SvgEdge[]; width: number; height: number }>(() => {
  if (!graph.value || !visibleGraphNodes.value.length) return { nodes: [], edges: [], width: 900, height: 480 }
  const cellWidth = 150
  const rowHeight = 82
  const laneGap = 56
  const groups = GRAPH_LANES.map((lane) => ({ ...lane, nodes: visibleGraphNodes.value.filter((node) => lane.kinds.includes(node.kind)) }))
  const knownKinds = new Set(GRAPH_LANES.flatMap((lane) => lane.kinds))
  const remaining = visibleGraphNodes.value.filter((node) => !knownKinds.has(node.kind))
  if (remaining.length) groups.push({ label: '其他事实', kinds: [], nodes: remaining })
  const maxColumns = Math.max(6, ...groups.map((group) => Math.ceil(Math.sqrt(group.nodes.length || 1))))
  const width = Math.max(900, maxColumns * cellWidth + 110)
  const pos = new Map<string, SvgNode>()
  let y = 52
  for (const group of groups) {
    const columns = Math.max(1, Math.ceil(Math.sqrt(group.nodes.length || 1)))
    group.nodes.forEach((node, index) => {
      const column = index % columns
      const row = Math.floor(index / columns)
      pos.set(node.id, {
        id: node.id,
        label: node.displayName.length > 24 ? `${node.displayName.slice(0, 24)}…` : node.displayName,
        kind: node.kind,
        x: 56 + column * cellWidth,
        y: y + row * rowHeight,
      })
    })
    y += Math.max(1, Math.ceil(group.nodes.length / columns)) * rowHeight + laneGap
  }
  const edges: SvgEdge[] = []
  for (const edge of visibleGraphEdges.value) {
    const source = pos.get(edge.sourceNodeId)
    const target = pos.get(edge.targetNodeId)
    if (source && target) edges.push({ x1: source.x, y1: source.y, x2: target.x, y2: target.y, type: edge.type })
  }
  return { nodes: [...pos.values()], edges, width, height: Math.max(480, y) }
})

function pageSlice<T>(items: T[], page: number, pageSize: number) {
  const start = (Math.max(1, page) - 1) * pageSize
  return items.slice(start, start + pageSize)
}
function scrollToComparison(element: HTMLElement | null) {
  void nextTick(() => element?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
watch([acDeltaFilter, acDeltaKeyword, acDeltaPageSize], () => { acDeltaPage.value = 1 })
watch([fusionDeltaFilter, fusionDeltaKeyword, fusionDeltaPageSize], () => { fusionDeltaPage.value = 1 })
watch([fusionKeyword, fusionStateFilter, fusionNodePageSize], () => { fusionNodePage.value = 1 })
watch([assertionKeyword, assertionVerdictFilter, assertionPageSize], () => { assertionPage.value = 1 })
watch([readModelKind, readModelKeyword, readModelPageSize], () => { readModelPage.value = 1 })
watch(comparison, () => {
  comparisonTab.value = 'ac'
  selectedAcDeltaId.value = ''
  selectedFusionDeltaKey.value = ''
  acDeltaPage.value = 1
  fusionDeltaPage.value = 1
  acDeltaFilter.value = ''
  fusionDeltaFilter.value = ''
  acDeltaKeyword.value = ''
  fusionDeltaKeyword.value = ''
})

onMounted(async () => {
  await Promise.all([loadGraph(), loadBaselines()])
})

async function loadBaselines() {
  try { baselines.value = (await fetchVerificationOverview(projectId.value)).baselines }
  catch { baselines.value = [] }
}

async function loadGraph() {
  if (loading.value || !baselineId.value) return
  loading.value = true; error.value = ''
  try {
    graph.value = await fetchGraphView(projectId.value, {
      baselineId: baselineId.value,
      focusId: focusId.value || undefined,
      depth: depth.value,
      maxNodes: clamp(maxNodes.value, 1, GRAPH_QUERY_MAX_NODES),
      maxEdges: clamp(maxEdges.value, 1, GRAPH_QUERY_MAX_EDGES),
    })
    nodeTablePage.value = 1
    await nextTick()
    requestAnimationFrame(updateGraphOverviewViewport)
  } catch (e) { error.value = msg(e) }
  finally { loading.value = false }
}

async function focusGraphNode(nodeId: string) {
  focusId.value = nodeId
  graphViewMode.value = 'graph'
  await loadGraph()
}

function updateGraphOverviewViewport() {
  const el = graphScrollRef.value
  const visuals = graphVisualsRef.value
  if (!el) return
  graphScrollState.value = {
    left: el.scrollLeft,
    top: el.scrollTop,
    width: el.clientWidth || 1,
    height: el.clientHeight || 1,
    scrollWidth: el.scrollWidth || 1,
    scrollHeight: el.scrollHeight || 1,
    visualLeft: visuals?.offsetLeft || 0,
    visualTop: visuals?.offsetTop || 0,
    visualWidth: visuals?.offsetWidth || 1,
    visualHeight: svgLayout.value.height || visuals?.offsetHeight || 1,
  }
}

function startGraphPan(event: PointerEvent) {
  const target = event.target as HTMLElement | SVGElement | null
  if (event.button !== 0 || target?.closest?.('.graph-overview')) return
  const el = graphScrollRef.value
  if (!el) return
  graphPanning.value = true
  graphPanStart.value = { x: event.clientX, y: event.clientY, left: el.scrollLeft, top: el.scrollTop }
  el.setPointerCapture?.(event.pointerId)
}

function moveGraphPan(event: PointerEvent) {
  if (!graphPanning.value) return
  const el = graphScrollRef.value
  if (!el) return
  const start = graphPanStart.value
  el.scrollLeft = start.left - (event.clientX - start.x)
  el.scrollTop = start.top - (event.clientY - start.y)
  updateGraphOverviewViewport()
}

function endGraphPan(event: PointerEvent) {
  if (!graphPanning.value) return
  graphPanning.value = false
  graphScrollRef.value?.releasePointerCapture?.(event.pointerId)
}

function startOverviewPan(event: PointerEvent) {
  overviewDragging.value = true
  ;(event.currentTarget as SVGElement).setPointerCapture?.(event.pointerId)
  panGraphFromOverview(event)
}

function moveOverviewPan(event: PointerEvent) {
  if (!overviewDragging.value) return
  panGraphFromOverview(event)
}

function endOverviewPan(event: PointerEvent) {
  overviewDragging.value = false
  ;(event.currentTarget as SVGElement).releasePointerCapture?.(event.pointerId)
}

function panGraphFromOverview(event: PointerEvent) {
  const el = graphScrollRef.value
  const visuals = graphVisualsRef.value
  const svg = event.currentTarget as SVGElement
  if (!el || !visuals || !svg) return
  const bounds = svg.getBoundingClientRect()
  const xRatio = clamp((event.clientX - bounds.left) / bounds.width, 0, 1)
  const yRatio = clamp((event.clientY - bounds.top) / bounds.height, 0, 1)
  const targetLeft = visuals.offsetLeft + xRatio * visuals.offsetWidth - el.clientWidth / 2
  const targetTop = visuals.offsetTop + yRatio * svgLayout.value.height - el.clientHeight / 2
  el.scrollLeft = clamp(targetLeft, 0, Math.max(0, el.scrollWidth - el.clientWidth))
  el.scrollTop = clamp(targetTop, 0, Math.max(0, el.scrollHeight - el.clientHeight))
  updateGraphOverviewViewport()
}

function showNodeTooltip(nodeId: string, event: MouseEvent) {
  hoverNode.value = nodeId
  updateNodeTooltipPosition(event)
}

function moveNodeTooltip(event: MouseEvent) {
  updateNodeTooltipPosition(event)
}

function hideNodeTooltip() {
  hoverNode.value = ''
}

function updateNodeTooltipPosition(event: MouseEvent) {
  const margin = 18
  const preferredWidth = 520
  const preferredHeight = 360
  tooltipPosition.value = {
    x: event.clientX,
    y: event.clientY,
    nearRight: event.clientX + preferredWidth + margin > window.innerWidth,
    nearBottom: event.clientY + preferredHeight + margin > window.innerHeight,
  }
}

async function runProjection(key: ProjectionKey) {
  if (busyKey.value) return
  busyKey.value = key
  try {
    const pid = projectId.value, bid = baselineId.value
    if (key === 'static') await projectStaticGraph(pid, bid)
    else if (key === 'cfg') await projectControlFlowGraph(pid, bid)
    else if (key === 'dependency') await projectStaticDependencyGraph(pid, bid)
    else if (key === 'coverage') await projectRuntimeCoverageGraph(pid, bid)
    else if (key === 'branch') await projectBranchCoverageGraph(pid, bid)
    else if (key === 'testExec') await projectTestExecutionGraph(pid, bid)
    else if (key === 'traceability') await projectTraceabilityGraph(pid, bid)
    toast.success('投影完成')
    await loadGraph()
  } catch (e) { toast.error(msg(e)) }
  finally { busyKey.value = '' }
}

async function loadFusion() {
  if (fusionLoading.value) return
  fusionLoading.value = true
  try {
    fusion.value = await fetchFusionView(projectId.value, baselineId.value, FUSION_VIEW_MAX_NODES)
    fusionNodePage.value = 1
  }
  catch (e) { toast.error(msg(e)) }
  finally { fusionLoading.value = false }
}

async function loadAssertion() {
  if (assertionLoading.value) return
  assertionLoading.value = true
  try {
    const aggregates = await fetchAssertionConsistency(projectId.value, baselineId.value)
    assertions.value = aggregates.map(aggregateToAssertion)
    assertionPage.value = 1
  } catch (e) { toast.error(msg(e)) }
  finally { assertionLoading.value = false }
}

async function runAssertion() {
  if (assertionLoading.value) return
  assertionLoading.value = true
  try {
    assertions.value = await evaluateAssertionConsistency(projectId.value, baselineId.value)
    assertionPage.value = 1
  }
  catch (e) { toast.error(msg(e)) }
  finally { assertionLoading.value = false }
}

async function doRebuildReadModels() {
  if (readModelBusy.value) return
  readModelBusy.value = true
  try {
    const r = await rebuildReadModels(projectId.value, baselineId.value)
    toast.success(`读模型已重建：AC覆盖 ${r.acCoverageRows} · 保护 ${r.symbolProtectionRows} · 热点 ${r.hotCallChainRows} · 未覆盖 ${r.uncoveredUnitRows}`)
    await loadReadModel()
  } catch (e) { toast.error(msg(e)) }
  finally { readModelBusy.value = false }
}

async function loadReadModel() {
  if (readModelBusy.value) return
  readModelBusy.value = true
  try {
    readModelRows.value = await fetchReadModel(projectId.value, baselineId.value, readModelKind.value)
    readModelPage.value = 1
  }
  catch (e) { toast.error(msg(e)) }
  finally { readModelBusy.value = false }
}

async function doCompare() {
  if (compareBusy.value || !compareBaseId.value) return
  compareBusy.value = true
  try {
    comparison.value = await compareBaselines(projectId.value, compareBaseId.value, baselineId.value)
    toast.success('基线比较完成')
  } catch (e) { toast.error(msg(e)) }
  finally { compareBusy.value = false }
}

function formatBaselineTime(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }

const evidenceCodeText = (value?: string) => {
  if (!value || value === '----' || value === '-') return '无关联依据'
  const labels: string[] = []
  if (value.includes('T')) labels.push('用例')
  if (value.includes('I')) labels.push('实现')
  if (value.includes('C')) labels.push('覆盖率')
  if (value.includes('E')) labels.push('执行')
  return labels.length ? labels.join('、') : value
}

function fusionDeltaKey(delta: FusionStateDelta, index: number) {
  return `${delta.symbol}|${delta.beforeState}|${delta.afterState}|${index}`
}

function acDeltaText(t: string) {
  return t === 'ADDED' ? '新增' : t === 'REMOVED' ? '移除' : '证据变化'
}
function acDeltaClass(t: string) {
  return t === 'REMOVED' ? 'failed' : 'passed'
}

function aggregateToAssertion(a: GraphAggregate): AssertionConsistencyResult {
  const p = a.payload || {}
  return {
    criterionId: a.subjectId,
    acKey: String(p.acKey ?? ''),
    verdict: (p.verdict === 'SUSPECTED_FALSE_PASS' ? 'SUSPECTED_FALSE_PASS' : 'ASSERTION_ALIGNED'),
    assertionOverlap: Number(p.bestAssertionOverlap ?? 0),
    bestTestcaseKey: p.bestTestcaseKey ? String(p.bestTestcaseKey) : undefined,
  }
}

const NODE_KIND_TEXT: Record<string, string> = {
  REQUIREMENT: '需求', ACCEPTANCE_CRITERION: '验收标准', TESTCASE: '用例', TEST_EXECUTION: '测试执行',
  SOURCE_FILE: '文件', TYPE: '类型', METHOD: '方法', FIELD: '字段', ENDPOINT: '接口', CONFIG: '配置',
  SQL_STATEMENT: 'SQL', BASIC_BLOCK: '基本块', DECISION: '判定', BRANCH: '分支', RUNTIME_SPAN: '调用跨度',
  COVERAGE_UNIT: '覆盖单元',
}
function nodeKindText(kind: string) { return NODE_KIND_TEXT[kind] || kind }
function fusionNodeText(state: string) {
  return state === 'EXECUTED_CONFIRMED' ? '已执行确认' : state === 'REACHABLE_NOT_EXECUTED' ? '可达未执行' : '不可观测'
}
function fusionStateText(state: string) {
  const m: Record<string, string> = { FUSED: '已融合', STATIC_ONLY: '仅静态', DYNAMIC_ONLY: '仅动态', EMPTY: '空' }
  return m[state] || state
}
function assertionVerdictText(v: string) { return v === 'SUSPECTED_FALSE_PASS' ? '疑似假通过' : '断言对齐' }
function readModelCells(payload: Record<string, unknown>): ReadModelCell[] {
  const count = (key: string) => `${Number(payload[key] ?? 0)} 条`
  const linked = (key: string) => payload[key] ? '已关联' : '缺失'
  const ids = (key: string) => `${Array.isArray(payload[key]) ? payload[key].length : 0} 条`
  if (readModelKind.value === 'RM_AC_COVERAGE_SUMMARY') return [
    { key: 'ac', value: `${String(payload.requirementKey ?? '-')} / ${String(payload.acKey ?? '-')}` },
    { key: 'testcase', value: linked('testcaseLinked') }, { key: 'implementation', value: linked('implementationLinked') },
    { key: 'coverage', value: linked('coverageLinked') }, { key: 'execution', value: linked('executionLinked') },
    { key: 'links', value: count('evidenceLinkCount') },
  ]
  if (readModelKind.value === 'RM_SYMBOL_TEST_PROTECTION') return [
    { key: 'symbol', value: String(payload.symbolId ?? '-'), mono: true }, { key: 'ac', value: ids('acIds') },
    { key: 'testcase', value: ids('testcaseIds') }, { key: 'protected', value: payload.protected ? '已受测试保护' : '尚无测试保护' },
  ]
  if (readModelKind.value === 'RM_HOT_CALL_CHAIN') return [
    { key: 'source', value: graphNodeLabel(String(payload.sourceNodeId ?? '-')) }, { key: 'target', value: graphNodeLabel(String(payload.targetNodeId ?? '-')) },
    { key: 'count', value: count('count') }, { key: 'duration', value: `${Number(payload.durationMs ?? 0)} ms` }, { key: 'outcome', value: String(payload.outcome || '未记录') },
  ]
  if (readModelKind.value === 'RM_UNCOVERED_UNITS') return [
    { key: 'unit', value: String(payload.displayName ?? payload.symbolId ?? '-'), mono: true },
    { key: 'location', value: String(payload.locator || '未记录'), mono: true, muted: !payload.locator }, { key: 'reason', value: String(payload.reason || '-') },
  ]
  if (readModelKind.value === 'RM_IMPACT_SUMMARY') return [
    { key: 'symbol', value: String(payload.changedSymbol ?? '-'), mono: true },
    { key: 'ac', value: `${Number(payload.affectedAcCount ?? 0)} 条` }, { key: 'testcase', value: `${Number(payload.affectedTestcaseCount ?? 0)} 条` },
  ]
  if (readModelKind.value === 'RM_QUALITY_GATE_SUMMARY') return [
    { key: 'criteria', value: count('totalCriteria') }, { key: 'testcase', value: metric(payload, 'testcaseCoveredCount', 'testcaseCoverageRate') },
    { key: 'implementation', value: metric(payload, 'implementationCoveredCount', 'implementationCoverageRate') }, { key: 'coverage', value: count('coverageCoveredCount') },
    { key: 'execution', value: count('executionCoveredCount') }, { key: 'closed', value: metric(payload, 'closedLoopCount', 'closedLoopRate') },
  ]
  return []
}
function graphNodeLabel(id: string) {
  const node = graphNodeById.value.get(id)
  return node?.displayName || id
}
function metric(payload: Record<string, unknown>, countKey: string, rateKey: string) {
  return `${Number(payload[countKey] ?? 0)} 条（${pct(Number(payload[rateKey] ?? 0))}）`
}
function pct(v: number) { return `${Math.round((v || 0) * 1000) / 10}%` }
function msg(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}
function isVisibleGraphNode(node: GraphNode) {
  if (!isGraphCodeKind(node.kind)) return true
  return isBusinessCodeItem({
    id: node.id,
    kind: node.kind,
    displayName: node.displayName,
    stableSymbolId: node.stableSymbolId,
    logicalSymbolId: node.logicalSymbolId,
    locator: node.locator,
    path: node.locator,
  })
}
function isBusinessReadModelRow(row: { id: string; cells: ReadModelCell[] }) {
  if (!['RM_SYMBOL_TEST_PROTECTION', 'RM_HOT_CALL_CHAIN', 'RM_UNCOVERED_UNITS', 'RM_IMPACT_SUMMARY'].includes(readModelKind.value)) return true
  return isBusinessCodeItem({
    id: row.id,
    displayName: row.cells.map((cell) => cell.value).join(' '),
    symbol: row.cells.find((cell) => cell.mono)?.value,
  })
}
function graphNodeSearchText(node: GraphNode) {
  return [
    node.id,
    node.kind,
    nodeKindText(node.kind),
    node.displayName,
    node.stableSymbolId,
    node.logicalSymbolId,
    node.locator,
    node.contentHash,
  ].filter(Boolean).join(' ').toLowerCase()
}
function graphNodeDisplayName(node: GraphNode) {
  if (node.kind === 'TEST_EXECUTION') {
    const testcase = stringAttribute(node, 'testcaseKey') || '未关联用例'
    return `用例 ${testcase} 的一次测试执行`
  }
  return node.displayName || nodeKindText(node.kind)
}
function graphNodeDescription(node: GraphNode) {
  const descriptions: Record<string, string> = {
    REQUIREMENT: '需求条目', ACCEPTANCE_CRITERION: '验收条件', TESTCASE: '测试用例', TEST_EXECUTION: '真实测试运行记录',
    SOURCE_FILE: '源码文件', TYPE: '代码类型', METHOD: '可执行方法', FIELD: '成员字段', ENDPOINT: '接口入口', CONFIG: '配置项',
    SQL_STATEMENT: 'SQL 语句', BASIC_BLOCK: '控制流基本块', DECISION: '条件判断', BRANCH: '条件分支', RUNTIME_SPAN: '运行调用片段', COVERAGE_UNIT: '覆盖率统计单元',
  }
  return descriptions[node.kind] || '图谱事实节点'
}
function graphNodeSourceInfo(node: GraphNode) {
  if (node.kind === 'TEST_EXECUTION') {
    const environment = stringAttribute(node, 'environment') || '未记录环境'
    const commit = stringAttribute(node, 'commit')
    return commit ? `${environment} · 提交 ${commit.slice(0, 8)}` : environment
  }
  return node.locator || (node.attributes?.runtimeOnly ? '仅在运行轨迹中发现' : '未记录位置')
}
function graphNodeRelationSummary(node: GraphNode) {
  const edges = graph.value?.edges.filter((edge) => edge.sourceNodeId === node.id || edge.targetNodeId === node.id) || []
  if (!edges.length) return '当前查询范围内暂无关联'
  const labels = [...new Set(edges.map((edge) => edgeTypeText(edge.type)))].slice(0, 2)
  return `${edges.length} 条关联：${labels.join('、')}${labels.length < new Set(edges.map((edge) => edge.type)).size ? '等' : ''}`
}
function stringAttribute(node: GraphNode, key: string) {
  const value = node.attributes?.[key]
  return value === undefined || value === null ? '' : String(value)
}
function edgeTypeText(type: string) {
  const labels: Record<string, string> = {
    HAS_AC: '包含验收标准', VERIFIED_BY: '由用例验证', EXECUTED_AS: '执行记录', IMPLEMENTED_BY: '由代码实现', EXERCISES: '用例覆盖',
    TOUCHED: '执行触达', COVERED: '覆盖', DECLARES: '声明', CONTAINS: '包含', IMPORTS: '导入', EXTENDS: '继承', IMPLEMENTS: '实现',
    INJECTS: '注入', CALLS_STATIC: '静态调用', CALLS_RUNTIME: '运行调用', OVERRIDES: '重写', READS: '读取', WRITES: '写入',
    ROUTES_TO: '路由到', QUERIES: '查询', CONFIGURES: '配置', PUBLISHES: '发布', CONSUMES: '消费', NORMAL: '正常流转',
    TRUE: '条件为真', FALSE: '条件为假', CASE: '分支条件', DEFAULT: '默认分支', LOOP_BACK: '循环回路', THROW: '抛出异常', CATCH: '捕获异常', FINALLY: '最终处理', RETURN: '返回',
  }
  return labels[type] || '关联'
}
function nodeTooltipRows(node: GraphNode) {
  const rows = [
    { label: '类型', value: nodeKindText(node.kind) },
    { label: '说明', value: graphNodeDescription(node) },
    { label: '位置 / 执行信息', value: graphNodeSourceInfo(node) },
    { label: '关联关系', value: graphNodeRelationSummary(node) },
  ]
  if (node.kind === 'TEST_EXECUTION') {
    const testcase = stringAttribute(node, 'testcaseKey')
    if (testcase) rows.splice(2, 0, { label: '关联用例', value: testcase })
  }
  return rows.filter((item) => item.value && item.value !== '未记录位置')
}
</script>
<style scoped>
.page-content { display: flex; flex-direction: column; gap: 16px; }
.page-header, .header-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.secondary-button, .secondary-button-link, .primary-button, .chip-button { display:inline-flex;align-items:center;min-height:34px;border-radius:8px;padding:6px 13px;font-weight:800;font-size:13px;text-decoration:none;cursor:pointer;border:1px solid var(--oat-border);background:var(--oat-surface-soft);color:var(--oat-text); }
.primary-button { border-color:var(--oat-primary);background:var(--oat-primary);color:#fff; }
.primary-button:disabled, .secondary-button:disabled, .chip-button:disabled { opacity:.45;cursor:not-allowed; }
.toolbar { display:flex;align-items:center;gap:8px;flex-wrap:wrap; }
.toolbar-label { font-size:12px;font-weight:700;color:var(--oat-text-muted); }
.summary-strip { display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 14px;border:1px solid var(--oat-border);border-radius:10px;background:#fff; }
.summary-chip { border-radius:999px;padding:3px 10px;font-size:12px;font-weight:800;background:rgba(100,116,139,.1);color:#64748b; }
.summary-chip.on { background:rgba(22,163,74,.12);color:#15803d; }
.summary-state { font-size:12px;color:var(--oat-text-muted);font-weight:700; }
.tabs { display:flex;gap:4px;border-bottom:2px solid var(--oat-border);flex-wrap:wrap; }
.tab { border:none;background:none;padding:8px 14px;font-size:14px;font-weight:800;color:var(--oat-text-muted);cursor:pointer;border-bottom:2px solid transparent;margin-bottom:-2px; }
.tab.active { color:var(--oat-primary);border-bottom-color:var(--oat-primary); }
.tab-panel { display:flex;flex-direction:column;gap:14px; }
.query-bar { display:flex;align-items:flex-end;gap:10px;flex-wrap:wrap; }
.field-inline { display:grid;gap:4px; }
.field-inline span { font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.field-inline input { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px;min-width:110px; }
.focus-field { flex:1 1 360px;min-width:280px; }
.focus-input-wrap { position:relative;display:flex;align-items:center; }
.focus-input-wrap input { width:100%;padding-right:34px; }
.focus-input-wrap button {
  position:absolute;
  right:7px;
  display:inline-flex;
  align-items:center;
  justify-content:center;
  width:22px;
  height:22px;
  border:0;
  border-radius:999px;
  background:rgba(100,116,139,.12);
  color:#64748b;
  cursor:pointer;
  font-size:16px;
  font-weight:900;
  line-height:1;
}
.focus-input-wrap button:hover { background:rgba(15,118,110,.12);color:var(--oat-primary-dark); }
.clip-banner { display:grid;gap:4px;padding:10px 14px;border:1px solid rgba(245,158,11,.3);border-radius:10px;background:rgba(245,158,11,.08); }
.clip-banner strong { color:#92400e;font-size:13px; }
.clip-banner ul { margin:0;padding-left:18px;font-size:12px;color:#92400e; }
.clip-banner small { font-size:11px;color:var(--oat-text-muted); }
.graph-stats { display:flex;gap:16px;font-size:13px;font-weight:700;color:var(--oat-text-secondary); }
.node-kind-legend { display:flex;gap:6px;flex-wrap:wrap;align-items:center; }
.view-toggle { display:inline-flex;margin-left:auto;border:1px solid var(--oat-border);border-radius:8px;overflow:hidden; }
.view-toggle button { border:none;background:#fff;padding:4px 12px;font-size:12px;font-weight:800;cursor:pointer;color:var(--oat-text-muted); }
.view-toggle button.active { background:var(--oat-primary);color:#fff; }
.projection-guide { display:flex;align-items:baseline;gap:8px 12px;flex-wrap:wrap;padding:10px 14px;border:1px solid rgba(var(--oat-primary-rgb),.18);border-radius:10px;background:rgba(var(--oat-primary-rgb),.04);font-size:12px;color:var(--oat-text-secondary);line-height:1.55; }
.projection-guide strong { color:var(--oat-primary-dark);font-size:13px; }
.feature-intro { margin:0;padding:10px 13px;border-left:3px solid var(--oat-primary);border-radius:0 8px 8px 0;background:var(--oat-surface-soft);color:var(--oat-text-secondary);font-size:13px;line-height:1.6; }
.feature-intro strong { color:var(--oat-text); }
.data-source-note { margin:0;padding:8px 10px;border:1px solid rgba(100,116,139,.18);border-radius:8px;background:#fff;color:var(--oat-text-muted);font-size:12px;line-height:1.55; }
.svg-canvas-wrap { position:relative;height:min(680px, calc(100vh - 210px));min-height:420px;border:1px solid var(--oat-border);border-radius:12px;background:#fff;padding:8px;overflow:auto;overscroll-behavior:contain;cursor:grab; }
.svg-canvas-wrap.panning { cursor:grabbing;user-select:none; }
.graph-explainer { margin:2px 2px 10px;padding:8px 10px;border-radius:8px;background:var(--oat-surface-soft);color:var(--oat-text-secondary);font-size:12px;line-height:1.5; }
.graph-visuals { position:relative;min-width:max-content; }
.graph-overview { position:sticky;top:8px;left:8px;z-index:4;display:block;width:180px;height:108px;padding:4px;border:1px solid var(--oat-border);border-radius:8px;background:rgba(255,255,255,.94);box-shadow:0 5px 16px rgba(15,23,42,.12);cursor:grab;touch-action:none; }
.graph-overview:active { cursor:grabbing; }
.mini-edge { stroke:rgba(100,116,139,.2);stroke-width:1; }
.overview-viewport { fill:rgba(20,184,166,.12);stroke:#0f766e;stroke-width:2;vector-effect:non-scaling-stroke;pointer-events:none;filter:drop-shadow(0 1px 2px rgba(15,118,110,.24)); }
.svg-canvas { min-width:900px;max-width:none;display:block;margin-top:8px; }
.svg-edge { stroke:rgba(100,116,139,.35);stroke-width:1; }
.svg-edge.et-CALLS_RUNTIME { stroke:rgba(37,99,235,.5); }
.svg-edge.et-COVERED { stroke:rgba(22,163,74,.5); }
.svg-edge.et-TRUE, .svg-edge.et-FALSE { stroke:rgba(217,70,239,.45); }
.svg-node-g { cursor:pointer; }
.svg-node { fill:#94a3b8;stroke:#fff;stroke-width:1.5;transition:r .1s ease; }
.svg-node.k-METHOD { fill:#2563eb; }
.svg-node.k-BRANCH { fill:#a21caf; }
.svg-node.k-TYPE { fill:#0f766e; }
.svg-node.k-TEST_EXECUTION { fill:#f59e0b; }
.svg-node.k-DECISION { fill:#db2777; }
.svg-node.k-COVERAGE_UNIT { fill:#16a34a; }
.svg-label { font-size:10px;fill:var(--oat-text);text-anchor:middle;font-weight:700;paint-order:stroke;stroke:#fff;stroke-width:3px; }
.svg-node-tooltip {
  position:fixed;
  z-index:80;
  width:min(520px, calc(100vw - 36px));
  max-height:min(420px, calc(100vh - 36px));
  overflow:auto;
  transform:translate(14px, 14px);
  display:grid;
  gap:8px;
  padding:10px 12px;
  border:1px solid rgba(15,23,42,.14);
  border-radius:8px;
  background:rgba(255,255,255,.98);
  color:var(--oat-text);
  box-shadow:0 18px 42px rgba(15,23,42,.18);
  pointer-events:none;
}
.svg-node-tooltip.near-right { transform:translate(calc(-100% - 12px), 12px); }
.svg-node-tooltip.near-bottom { transform:translate(12px, calc(-100% - 12px)); }
.svg-node-tooltip.near-right.near-bottom { transform:translate(calc(-100% - 12px), calc(-100% - 12px)); }
.svg-node-tooltip strong { font-size:13px;line-height:1.35;overflow-wrap:anywhere; }
.svg-node-tooltip dl { display:grid;grid-template-columns:auto minmax(0, 1fr);gap:4px 8px;margin:0;font-size:12px;line-height:1.45; }
.svg-node-tooltip dt { color:var(--oat-text-muted);font-weight:800;white-space:nowrap; }
.svg-node-tooltip dd { margin:0;overflow-wrap:anywhere;white-space:pre-wrap; }
.badge-count { border-radius:999px;padding:1px 8px;font-size:11px;font-weight:900;background:rgba(37,99,235,.12);color:#1d4ed8; }
.kind-chip, .kind-tag { border-radius:6px;padding:2px 8px;font-size:11px;font-weight:800;background:rgba(100,116,139,.1);color:#475569; }
.kind-tag.k-METHOD, .kind-chip.k-METHOD { background:rgba(37,99,235,.12);color:#1d4ed8; }
.kind-tag.k-BRANCH, .kind-chip.k-BRANCH { background:rgba(217,70,239,.12);color:#a21caf; }
.kind-tag.k-TYPE, .kind-chip.k-TYPE { background:rgba(13,148,136,.12);color:#0f766e; }
.kind-tag.k-TEST_EXECUTION, .kind-chip.k-TEST_EXECUTION { background:rgba(245,158,11,.14);color:#92400e; }
.result-table-block { display:grid;gap:10px;border:1px solid var(--oat-border);border-radius:8px;background:#fff;padding:10px; }
.result-table-head { display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap; }
.result-table-head strong { font-size:13px;color:var(--oat-text); }
.result-table-head span { font-size:12px;font-weight:700;color:var(--oat-text-muted); }
.table-scroll { max-height:min(520px, calc(100vh - 280px));min-height:160px;overflow:auto;border:1px solid var(--oat-border);border-radius:8px;background:#fff; }
.data-table { width:100%;border-collapse:separate;border-spacing:0;font-size:13px; }
.data-table th { position:sticky;top:0;z-index:1;padding:8px 10px;border-bottom:1px solid var(--oat-border);text-align:left;font-size:12px;color:var(--oat-text-muted);background:#f8fafc;white-space:nowrap; }
.data-table td { padding:8px 10px;border-bottom:1px solid var(--oat-border);vertical-align:top;overflow-wrap:anywhere; }
.data-table tbody tr:last-child td { border-bottom:0; }
.data-table tbody tr:hover td { background:rgba(15,118,110,.035); }
.list-toolbar { display:flex;align-items:center;gap:8px;flex-wrap:wrap; }
.list-toolbar input {
  flex:1 1 280px;
  min-height:34px;
  border:1px solid var(--oat-border);
  border-radius:8px;
  padding:6px 10px;
  background:#fff;
  color:var(--oat-text);
  font-size:13px;
}
.inline-table-button {
  border:1px solid var(--oat-border);
  border-radius:7px;
  background:#fff;
  color:var(--oat-primary-dark);
  cursor:pointer;
  font-size:12px;
  font-weight:800;
}
.inline-table-button { min-height:28px;padding:3px 9px;white-space:nowrap; }
.inline-table-button:disabled { opacity:.45;cursor:not-allowed; }
.graph-node-table { min-width:920px; }
.fusion-table { min-width:760px; }
.assertion-table { min-width:720px; }
.read-model-table { min-width:920px; }
.graph-node-table th:nth-child(1) { width:84px; }
.graph-node-table th:nth-child(2) { min-width:180px; }
.graph-node-table th:nth-child(3) { min-width:110px; }
.graph-node-table th:nth-child(4) { min-width:180px; }
.graph-node-table th:nth-child(5) { min-width:150px; }
.node-name { color:var(--oat-text);font-weight:700;word-break:break-word; }
.id-cell { max-width:260px;overflow-wrap:anywhere; }
.mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px; }
.mono.muted, .muted { color:var(--oat-text-muted); }
.mono.payload { word-break:break-all; }
.table-note { font-size:12px;color:var(--oat-text-muted); }
.fusion-summary { display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px; }
.fusion-card { display:grid;gap:2px;padding:14px;border:1px solid var(--oat-border);border-radius:10px;text-align:center;background:#fff; }
.fusion-card .num { font-size:26px;font-weight:900; }
.fusion-card.confirmed { border-color:rgba(22,163,74,.3);color:#15803d; }
.fusion-card.reachable { border-color:rgba(245,158,11,.3);color:#92400e; }
.fusion-card.observable { border-color:rgba(100,116,139,.3);color:#475569; }
.fusion-card.total { border-color:rgba(37,99,235,.3);color:#1d4ed8; }
.fusion-tag, .verdict-chip { border-radius:999px;padding:2px 8px;font-size:11px;font-weight:800; }
.fusion-tag.executed_confirmed { background:rgba(22,163,74,.12);color:#15803d; }
.fusion-tag.reachable_not_executed { background:rgba(245,158,11,.14);color:#92400e; }
.fusion-tag.not_observable { background:rgba(100,116,139,.12);color:#475569; }
.verdict-chip.passed { background:rgba(22,163,74,.1);color:#15803d; }
.verdict-chip.failed { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.policy-select { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px; }
.comparison-summary { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px; }
.comparison-summary article { display:grid;gap:4px;padding:13px 14px;border:1px solid var(--oat-border);border-radius:10px;background:#fff; }
.comparison-summary span,.comparison-summary small { color:var(--oat-text-muted);font-size:12px; }
.comparison-summary strong { color:var(--oat-primary-dark);font-size:20px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }
.comparison-card { min-width:0; }
.comparison-card-head { display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap; }
.comparison-tabs { display:flex;gap:4px;border-bottom:1px solid var(--oat-border); }
.comparison-tabs button { display:inline-flex;align-items:center;gap:6px;border:0;border-bottom:2px solid transparent;background:transparent;padding:8px 12px;color:var(--oat-text-muted);cursor:pointer;font-size:13px;font-weight:900; }
.comparison-tabs button.active { border-bottom-color:var(--oat-primary);color:var(--oat-primary-dark); }
.comparison-tabs span { border-radius:999px;padding:1px 7px;background:rgba(100,116,139,.12);font-size:11px; }
.comparison-detail { display:grid;gap:8px;padding:12px;border:1px solid rgba(var(--oat-primary-rgb),.16);border-radius:10px;background:rgba(var(--oat-primary-rgb),.04); }
.comparison-detail h4 { margin:0;font-size:14px;color:var(--oat-text); }
.comparison-detail dl { display:grid;grid-template-columns:120px minmax(0,1fr);gap:7px 10px;margin:0;font-size:12px;line-height:1.5; }
.comparison-detail dt { color:var(--oat-text-muted);font-weight:900; }
.comparison-detail dd { margin:0;overflow-wrap:anywhere; }
.comparison-detail code { margin-left:6px;color:var(--oat-text-muted);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px; }
.compact-select { max-width:160px; }
.compact-search { min-height:34px;min-width:min(320px,100%);border:1px solid var(--oat-border);border-radius:8px;padding:6px 10px;background:#fff;color:var(--oat-text);font-size:13px; }
.comparison-table-wrap { max-height:520px;overflow:auto;border:1px solid var(--oat-border);border-radius:8px; }
.comparison-table-wrap .data-table { min-width:620px; }
.comparison-table-wrap .data-table th { position:sticky;top:0;background:#fff;z-index:1; }
.ops-block { display:grid;gap:10px;padding:16px;border:1px solid var(--oat-border);border-radius:12px;background:#fff; }
.section-title { margin:0;font-size:15px; }
.ops-hint { margin:0;font-size:12px;color:var(--oat-text-muted); }
.ops-textarea { border:1px solid var(--oat-border);border-radius:8px;padding:8px 10px;font-size:13px;font-family:ui-monospace,monospace;resize:vertical; }
.ops-result { padding:10px 12px;border-radius:8px;background:var(--oat-surface-soft);font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.diff-grid { display:flex;gap:8px;flex-wrap:wrap; }
.notice.danger { border:1px solid rgba(220,38,38,.25);border-radius:10px;padding:10px 14px;color:var(--oat-danger);background:rgba(220,38,38,.06); }
.notice.warn { border:1px solid rgba(245,158,11,.3);border-radius:10px;padding:10px 14px;color:#92400e;background:rgba(245,158,11,.08);font-size:13px; }
.empty-state { display:grid;gap:8px;min-height:140px;place-content:center;text-align:center;border:1px dashed var(--oat-border);border-radius:12px;padding:24px; }
.empty-state strong { font-size:16px; }
.empty-state span { color:var(--oat-text-muted);font-size:13px; }
@media (max-width: 900px) {
  .comparison-summary { grid-template-columns:1fr; }
  .comparison-detail dl { grid-template-columns:1fr; }
}
</style>
