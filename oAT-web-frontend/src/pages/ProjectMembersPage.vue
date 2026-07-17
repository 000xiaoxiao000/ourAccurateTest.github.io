<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Members</div>
        <h1>项目成员</h1>
      </div>
      <AppRefreshButton :loading="loading" @click="load" />
    </div>

    <div v-if="loading" class="status-card">正在加载成员数据...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <div class="toolbar">
        <input v-model.trim="keyword" class="select search-input" type="search" placeholder="搜索成员名称、邮箱或角色" aria-label="搜索项目成员" />
        <select v-model="selectedUserId" class="select">
          <option value="">选择要添加的用户</option>
          <option v-for="user in payload.availableUsers" :key="user.id" :value="user.id">
            {{ user.name }}{{ user.email ? ` (${user.email})` : '' }}
          </option>
        </select>
        <button class="primary-button" type="button" :disabled="!selectedUserId" @click="addMember">
          添加成员
        </button>
      </div>

      <div class="table-card">
        <div class="table-summary">显示 {{ filteredMembers.length }} / {{ payload.members.length }} 个成员</div>
        <table class="table">
          <thead>
            <tr>
              <th>成员</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in paginatedMembers" :key="member.id">
              <td>{{ member.memberName || member.memberId }}</td>
              <td>{{ member.memberEmail || '-' }}</td>
              <td>
                <template v-if="member.role === 'owner'">
                  <span class="owner-chip">owner</span>
                </template>
                <template v-else>
                  <select
                    class="table-select"
                    :value="member.role || 'visitor'"
                    @change="changeRole(member.id, ($event.target as HTMLSelectElement).value)"
                  >
                    <option value="admin">admin</option>
                    <option value="normal">normal</option>
                    <option value="visitor">visitor</option>
                  </select>
                </template>
              </td>
              <td>
                <button
                  v-if="member.role !== 'owner'"
                  class="danger-button"
                  type="button"
                  @click="removeMemberAction(member.id)"
                >
                  移除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!filteredMembers.length" class="empty-card">暂无成员或没有匹配结果</div>
      </div>
      <AppPagination
        v-if="filteredMembers.length > 0"
        v-model:page="currentPage"
        v-model:page-size="pageSize"
        :total="filteredMembers.length"
        item-name="成员"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const payload = computed(() => projectStore.membersByProjectId[projectId.value])
const loading = ref(false)
const error = ref('')
const selectedUserId = ref('')
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)

const filteredMembers = computed(() => {
  const members = payload.value?.members || []
  const term = keyword.value.toLowerCase()
  if (!term) return members
  return members.filter((member) => [
    member.memberName,
    member.memberId,
    member.memberEmail,
    member.role,
  ].some((value) => String(value || '').toLowerCase().includes(term)))
})

const paginatedMembers = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredMembers.value.slice(start, start + pageSize.value)
})

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectMembers(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载成员失败'
  } finally {
    loading.value = false
  }
}

async function addMember() {
  if (!selectedUserId.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.addMembers(projectId.value, [selectedUserId.value])
    selectedUserId.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : '添加成员失败'
  } finally {
    loading.value = false
  }
}

async function removeMemberAction(memberId: string) {
  loading.value = true
  error.value = ''
  try {
    await projectStore.deleteMember(projectId.value, memberId)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '移除成员失败'
  } finally {
    loading.value = false
  }
}

async function changeRole(memberId: string, role: string) {
  loading.value = true
  error.value = ''
  try {
    await projectStore.changeMemberRole(projectId.value, memberId, role)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '修改角色失败'
  } finally {
    loading.value = false
  }
}

watch(keyword, () => {
  currentPage.value = 1
})

watch(pageSize, () => {
  currentPage.value = 1
})

watch(() => filteredMembers.value.length, (total) => {
  const totalPages = Math.max(1, Math.ceil(total / pageSize.value))
  if (currentPage.value > totalPages) {
    currentPage.value = totalPages
  }
})

onMounted(load)
</script>

<style scoped>
.page-header,
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.page-header {
  margin-bottom: 20px;
}

.toolbar {
  position: sticky;
  top: 12px;
  z-index: 4;
  margin-bottom: 16px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.06);
}


.status-card,
.table-card,
.empty-card {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.select,
.table-select {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}

.select {
  min-width: 280px;
}

.search-input {
  flex: 1;
  min-width: 260px;
}

.table-card {
  max-height: min(620px, calc(100vh - 280px));
  overflow: auto;
}

.table-summary {
  margin-bottom: 10px;
  color: #64748b;
  font-size: 13px;
  font-weight: 700;
}

.empty-card {
  margin-top: 12px;
  text-align: center;
  color: #64748b;
}

.action-button,
.primary-button,
.danger-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  color: #fff;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
}

.primary-button {
  background: #0f766e;
}

.danger-button {
  background: #b91c1c;
}

.table {
  width: 100%;
  border-collapse: collapse;
}

.table th,
.table td {
  padding: 14px 10px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  text-align: left;
}

.table th {
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.owner-chip {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(79, 70, 229, 0.1);
  color: #4338ca;
  font-size: 12px;
}

@media (max-width: 860px) {
  .page-header,
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .select,
  .search-input {
    min-width: 0;
    width: 100%;
  }
}
</style>
