<script setup lang="ts">
import type { TrafficFilterRule } from '../types/traffic'

const props = defineProps<{
  rules: TrafficFilterRule[]
}>()

const emit = defineEmits<{
  save: [rules: TrafficFilterRule[]]
}>()

const targets: Array<TrafficFilterRule['target']> = ['url', 'method', 'protocol', 'statusCode', 'header', 'body']
const operators: Array<TrafficFilterRule['operator']> = ['contains', 'equals', 'regex', 'startsWith', 'endsWith']
const actions: Array<TrafficFilterRule['action']> = ['mark', 'include', 'exclude']

function cloneRules(): TrafficFilterRule[] {
  return props.rules.map(rule => ({ ...rule }))
}

function addRule() {
  const rules = cloneRules()
  rules.push({
    id: `rule-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name: '新规则',
    enabled: true,
    target: 'url',
    operator: 'contains',
    value: '',
    action: 'mark'
  })
  emit('save', rules)
}

function updateRule(index: number, patch: Partial<TrafficFilterRule>) {
  const rules = cloneRules()
  rules[index] = { ...rules[index], ...patch }
  emit('save', rules)
}

function deleteRule(index: number) {
  const rules = cloneRules()
  rules.splice(index, 1)
  emit('save', rules)
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2>过滤规则</h2>
      <button class="small-btn primary" type="button" @click="addRule">新增规则</button>
    </div>
    <div v-if="rules.length === 0" class="empty-line">暂无自定义规则</div>
    <div v-for="(rule, index) in rules" :key="rule.id" class="rule-row">
      <label class="switch">
        <input :checked="rule.enabled" type="checkbox" @change="updateRule(index, { enabled: ($event.target as HTMLInputElement).checked })" />
        <span>启用</span>
      </label>
      <input :value="rule.name" class="text-input name-input" type="text" @input="updateRule(index, { name: ($event.target as HTMLInputElement).value })" />
      <select :value="rule.target" @change="updateRule(index, { target: ($event.target as HTMLSelectElement).value as TrafficFilterRule['target'] })">
        <option v-for="target in targets" :key="target" :value="target">{{ target }}</option>
      </select>
      <select :value="rule.operator" @change="updateRule(index, { operator: ($event.target as HTMLSelectElement).value as TrafficFilterRule['operator'] })">
        <option v-for="operator in operators" :key="operator" :value="operator">{{ operator }}</option>
      </select>
      <input :value="rule.value" class="text-input" type="text" placeholder="匹配值" @input="updateRule(index, { value: ($event.target as HTMLInputElement).value })" />
      <select :value="rule.action" @change="updateRule(index, { action: ($event.target as HTMLSelectElement).value as TrafficFilterRule['action'] })">
        <option v-for="action in actions" :key="action" :value="action">{{ action }}</option>
      </select>
      <button class="small-btn danger" type="button" @click="deleteRule(index)">删除</button>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: #ffffff;
  border-bottom: 1px solid #e8e8e8;
  padding: 14px 30px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

h2 {
  margin: 0;
  font-size: 15px;
  color: #262626;
}

.rule-row {
  display: grid;
  grid-template-columns: 74px 140px 120px 120px minmax(180px, 1fr) 100px 64px;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  color: #595959;
}

.text-input,
select {
  height: 32px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  background: #fff;
}

.small-btn {
  height: 32px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 10px;
  background: #fff;
  cursor: pointer;
}

.small-btn.primary {
  background: #667eea;
  border-color: #667eea;
  color: #fff;
}

.small-btn.danger {
  color: #ff4d4f;
}

.empty-line {
  color: #8c8c8c;
  font-size: 13px;
}

@media (max-width: 960px) {
  .rule-row {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
