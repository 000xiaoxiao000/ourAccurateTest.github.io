<template>
  <section class="search-page">
    <form class="search-form" @submit.prevent="submitSearch">
      <div class="action-input">
        <input
          ref="searchInputRef"
          v-model.trim="searchText"
          type="search"
          aria-label="搜索中心关键词"
          maxlength="120"
          placeholder="搜索用例..."
          @keydown.enter.prevent="submitSearch"
        />
        <button class="search-button" type="submit" :disabled="loading">{{ loading ? '搜索中...' : '搜索' }}</button>
      </div>
    </form>

    <div v-if="error" class="message error">{{ error }}</div>

    <section class="keyword-results">
      <div v-if="!searched" class="placeholder-state">
        <strong>⌕</strong>
        <span>输入关键词搜索测试用例</span>
      </div>
      <template v-else>
        <div class="result-count">为您找到：{{ results?.total || 0 }} 条结果</div>
        <div v-if="!results?.results?.length" class="placeholder-state compact">
          <strong>⌕</strong>
          <span>没有找到匹配结果</span>
        </div>
        <div v-else class="search-items">
          <RouterLink
            v-for="item in paginatedResults"
            :key="item.id"
            class="search-item"
            :to="item.targetPath"
            target="_blank"
          >
            <div class="item-image">
              <img :src="resultImage(item)" alt="" loading="lazy" />
            </div>
            <div class="item-content">
              <div class="item-title" v-html="item.titleFragment || item.title || item.plainTitle || '-'" />
              <div class="item-desc">
                <template v-if="hitFragments(item).length">
                  <span v-for="fragment in hitFragments(item)" :key="fragment" v-html="fragment"></span>
                </template>
                <span v-else>{{ item.subTitle || item.directoryPath || '暂无命中片段' }}</span>
              </div>
            </div>
          </RouterLink>
        </div>
        <AppPagination
          v-if="resultItems.length > pageSize"
          v-model:page="page"
          v-model:page-size="pageSize"
          :total="resultItems.length"
          item-name="条结果"
        />
      </template>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { searchKeyword } from '@/api/bootstrap'
import type { SearchKeywordResult } from '@/api/types'
import AppPagination from '@/components/AppPagination.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId || ''))
const keyword = ref(String(route.query.keyword || route.query.q || ''))
const searchText = ref(keyword.value)
const error = ref('')
const loading = ref(false)
const searched = ref(false)
const results = ref<Awaited<ReturnType<typeof searchKeyword>> | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const page = ref(1)
const pageSize = ref(10)

const resultItems = computed(() => results.value?.results || [])
const paginatedResults = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return resultItems.value.slice(start, start + pageSize.value)
})

watch([results, pageSize], () => {
  page.value = 1
})

onMounted(() => {
  if (keyword.value) submitKeywordSearch()
})

function updateQuery(value: string) {
  const query = { ...route.query }
  if (value) query.keyword = value
  else delete query.keyword
  delete query.q
  delete query.tab
  router.replace({ query }).catch(() => undefined)
}

function resultImage(item: SearchKeywordResult) {
  return item.imagePath || (item.headImage ? `/r/${item.headImage}` : '/images/image.png')
}

function hitFragments(item: SearchKeywordResult) {
  return [
    ...(item.describeFragments || []),
    ...(item.sqlContentFragments || []),
    ...(item.remoteContentFragments || []),
  ]
}

function submitSearch() {
  keyword.value = searchText.value
  submitKeywordSearch()
}

async function submitKeywordSearch() {
  const value = keyword.value.trim()
  if (!value) {
    error.value = '请输入搜索关键字'
    searchInputRef.value?.focus()
    return
  }
  loading.value = true
  searched.value = true
  error.value = ''
  try {
    results.value = await searchKeyword(projectId.value, value, 'usecase')
    page.value = 1
    updateQuery(value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '搜索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped src="@/features/search/styles/search-center-page.css"></style>
