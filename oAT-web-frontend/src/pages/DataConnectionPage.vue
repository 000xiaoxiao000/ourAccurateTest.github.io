<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 数据连接</div>
        <h1>数据连接</h1>
        <p class="subtext">查看平台支持的连接器类型和接入方式。外部资料通过文件导入或 API 连接器接入分析引擎。</p>
      </div>
    </header>

    <div class="connection-grid">
      <article class="connection-card active">
        <div class="conn-icon">📄</div>
        <h3>文件 / 粘贴导入</h3>
        <p>支持 Word、Markdown、Excel、CSV、XMind、JSON、源码压缩包和覆盖率报告。无需 API 即可完成一次性分析批次。</p>
        <div class="conn-badge">L2 · 可重复分析</div>
        <RouterLink :to="`/p/${projectId}/verification`" class="conn-action primary-button">去导入资料</RouterLink>
      </article>

      <article class="connection-card active">
        <div class="conn-icon">🔧</div>
        <h3>Git 源码连接器</h3>
        <p>通过已配置的源码工程，按分支和 Commit 拉取源码快照作为代码分析资料。支持 GitHub、GitLab、Gitee 等。</p>
        <div class="conn-badge">L2 · 可重复分析</div>
        <RouterLink :to="`/p/${projectId}/verification`" class="conn-action primary-button">去导入源码</RouterLink>
      </article>

      <article class="connection-card planned">
        <div class="conn-icon">🐛</div>
        <h3>Jira 连接器</h3>
        <p>通过 Jira REST API 拉取需求、Epic、Story 和 Bug。支持字段映射和 Webhook 增量同步。</p>
        <div class="conn-badge planned-badge">L3 · 持续验证 · 计划中</div>
        <span class="conn-action disabled-button">待接入</span>
      </article>

      <article class="connection-card planned">
        <div class="conn-icon">📋</div>
        <h3>禅道 / TAPD / PingCode</h3>
        <p>通过对应平台 REST API 拉取需求、测试用例和缺陷，建立持续验证和增量分析闭环。</p>
        <div class="conn-badge planned-badge">L3 · 持续验证 · 计划中</div>
        <span class="conn-action disabled-button">待接入</span>
      </article>

      <article class="connection-card planned">
        <div class="conn-icon">🧪</div>
        <h3>TestRail / Xray / MeterSphere</h3>
        <p>通过测试管理平台 API 拉取用例库、测试计划和执行结果，提供执行依据和覆盖率数据。</p>
        <div class="conn-badge planned-badge">L3 · 持续验证 · 计划中</div>
        <span class="conn-action disabled-button">待接入</span>
      </article>

      <article class="connection-card planned">
        <div class="conn-icon">🔄</div>
        <h3>CI / CD 执行依据</h3>
        <p>通过 Jenkins、GitLab CI、GitHub Actions 接入测试执行结果和 JaCoCo/Istanbul 覆盖率报告。</p>
        <div class="conn-badge planned-badge">L3 · 持续验证 · 计划中</div>
        <span class="conn-action disabled-button">待接入</span>
      </article>
    </div>

    <section class="input-level-section">
      <h2>接入成熟度等级说明</h2>
      <div class="level-table">
        <div class="level-row header-row">
          <span>等级</span>
          <span>输入方式</span>
          <span>支持能力</span>
        </div>
        <div class="level-row">
          <strong>L1 临时分析</strong>
          <span>粘贴、临时文件</span>
          <span>单次 AI 分析和报告，无基线版本管理</span>
        </div>
        <div class="level-row">
          <strong>L2 可重复分析</strong>
          <span>标准文件、明确 Git Commit</span>
          <span>基线冻结、人工触发重新验证、变更影响分析</span>
        </div>
        <div class="level-row">
          <strong>L3 持续验证</strong>
          <span>API 连接器、Webhook 或客户侧 Agent</span>
          <span>自动变更感知、增量分析、受控回写、质量门禁</span>
        </div>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
</script>

<style scoped>
.page-content { display: flex; flex-direction: column; gap: 20px; }

.connection-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.connection-card {
  display: grid;
  gap: 10px;
  padding: 18px;
  border: 1px solid var(--oat-border);
  border-radius: 14px;
  background: #fff;
}

.connection-card.planned {
  background: var(--oat-surface-soft);
  opacity: .85;
}

.conn-icon { font-size: 28px; }

.connection-card h3 { margin: 0; font-size: 16px; }

.connection-card p {
  margin: 0;
  color: var(--oat-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.conn-badge {
  display: inline-flex;
  align-self: start;
  border-radius: 999px;
  padding: 3px 10px;
  background: rgba(var(--oat-primary-rgb), .1);
  color: var(--oat-primary-dark);
  font-size: 11px;
  font-weight: 800;
}

.planned-badge {
  background: rgba(100, 116, 139, .1);
  color: #64748b;
}

.conn-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  border-radius: 8px;
  padding: 7px 14px;
  font-weight: 800;
  font-size: 14px;
  text-decoration: none;
}

.primary-button {
  border: 1px solid var(--oat-primary);
  background: var(--oat-primary);
  color: #fff;
}

.disabled-button {
  border: 1px solid var(--oat-border);
  background: var(--oat-surface-soft);
  color: var(--oat-text-muted);
  cursor: not-allowed;
}

.input-level-section { display: grid; gap: 12px; }
.input-level-section h2 { margin: 0; font-size: 16px; }

.level-table {
  display: grid;
  border: 1px solid var(--oat-border);
  border-radius: 12px;
  overflow: hidden;
}

.level-row {
  display: grid;
  grid-template-columns: 180px 220px 1fr;
  gap: 16px;
  padding: 12px 16px;
  border-top: 1px solid var(--oat-border);
  font-size: 13px;
}

.level-row:first-child { border-top: 0; }

.header-row {
  background: var(--oat-surface-soft);
  font-weight: 800;
  color: var(--oat-text-secondary);
}

@media (max-width: 720px) {
  .level-row { grid-template-columns: 1fr; gap: 4px; }
}
</style>
