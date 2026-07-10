<script setup lang="ts">
import { ref } from 'vue'
import type { BuiltinPluginId, PluginInfo } from '../types/traffic'

defineProps<{
  plugins: PluginInfo[]
  pluginsPath: string
}>()

const emit = defineEmits<{
  reload: []
  openFolder: []
  installBuiltin: [pluginId: BuiltinPluginId]
  uninstallBuiltin: []
  uninstallPlugin: [plugin: PluginInfo]
}>()

const builtinPluginOptions: Array<{ id: BuiltinPluginId; label: string }> = [
  { id: 'all', label: '全部内置插件' },
  { id: 'traffic-cleanup-plugin', label: '流量清洗插件' },
  { id: 'oat-coverage-relay', label: '前端覆盖率中继插件' }
]
const selectedBuiltinPlugin = ref<BuiltinPluginId>('all')

function requestUninstall(plugin: PluginInfo) {
  if (!confirm(`确定卸载插件「${plugin.name}」？插件目录将被删除。`)) return
  emit('uninstallPlugin', plugin)
}

function installSelectedBuiltinPlugin() {
  emit('installBuiltin', selectedBuiltinPlugin.value)
}
</script>

<template>
  <section class="plugin-panel">
    <div class="panel-head">
      <div>
        <h2>插件扩展</h2>
        <p>{{ pluginsPath || '加载中...' }}</p>
        <p class="explain">这里的插件是 oAT 应用内部插件，不是浏览器插件。插件只处理本工具捕获到的流量记录。</p>
      </div>
      <div class="actions">
        <button class="small-btn secondary" type="button" @click="emit('openFolder')">打开目录</button>
        <select v-model="selectedBuiltinPlugin" class="plugin-select">
          <option v-for="option in builtinPluginOptions" :key="option.id" :value="option.id">
            {{ option.label }}
          </option>
        </select>
        <button class="small-btn secondary" type="button" @click="installSelectedBuiltinPlugin">安装</button>
        <button class="small-btn secondary danger" type="button" @click="emit('uninstallBuiltin')">卸载内置插件</button>
        <button class="small-btn primary" type="button" @click="emit('reload')">重新加载</button>
      </div>
    </div>
    <div class="builtin-info">
      <strong>内置插件：流量清洗 + 前端覆盖率中继</strong>
      <span>过滤 OPTIONS/静态资源，并识别 Istanbul 覆盖率请求附加用例名后转发。</span>
    </div>
    <div v-if="plugins.length === 0" class="empty-line">插件目录中暂无插件</div>
    <div v-for="plugin in plugins" :key="plugin.id" class="plugin-row">
      <div>
        <strong>{{ plugin.name }}</strong>
        <span>{{ plugin.version }}</span>
      </div>
      <span class="state" :class="{ off: !plugin.enabled, error: plugin.error }">
        {{ plugin.error ? '异常' : plugin.enabled ? '启用' : '停用' }}
      </span>
      <p v-if="plugin.description">{{ plugin.description }}</p>
      <p v-if="plugin.error" class="error-text">{{ plugin.error }}</p>
      <div class="plugin-actions">
        <button class="small-btn secondary danger" type="button" @click="requestUninstall(plugin)">卸载此插件</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.plugin-panel {
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  padding: 14px 30px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

h2 {
  margin: 0;
  font-size: 15px;
  color: #262626;
}

p {
  margin: 4px 0 0;
  color: #8c8c8c;
  font-size: 12px;
  word-break: break-all;
}

.small-btn {
  height: 32px;
  border: 1px solid #667eea;
  border-radius: 4px;
  padding: 0 10px;
  background: #667eea;
  color: #fff;
  cursor: pointer;
}

.small-btn.secondary {
  background: #fff;
  color: #595959;
  border-color: #d9d9d9;
}

.small-btn.danger {
  color: #ff4d4f;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.plugin-select {
  height: 32px;
  min-width: 150px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 8px;
  background: #fff;
  color: #595959;
  font-size: 13px;
}

.plugin-row {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 10px;
  margin-top: 8px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 12px;
}

.plugin-row strong {
  color: #262626;
  margin-right: 8px;
}

.plugin-row span {
  color: #8c8c8c;
  font-size: 12px;
}

.state {
  color: #52c41a;
}

.state.off {
  color: #8c8c8c;
}

.state.error,
.error-text {
  color: #ff4d4f;
}

.plugin-row p {
  grid-column: 1 / -1;
}

.plugin-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
}

.empty-line {
  color: #8c8c8c;
  font-size: 13px;
}

.explain {
  color: #595959;
}

.builtin-info {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 8px 10px;
  border: 1px solid #e6f7ff;
  border-radius: 4px;
  background: #f0f8ff;
  color: #595959;
  font-size: 12px;
  margin-bottom: 8px;
}

.builtin-info strong {
  color: #262626;
}
</style>
