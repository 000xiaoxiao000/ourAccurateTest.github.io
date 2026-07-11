import { computed, ref, type ComputedRef } from 'vue'

import type { AiChatSession } from '@/features/ai/types'

type SessionSortMode = 'recent' | 'oldest' | 'name'

type UseProjectAiSessionsOptions = {
  projectId: ComputedRef<string>
  persist: (projectId: string, sessionState: string) => Promise<unknown>
  promptTitle: (currentTitle: string) => Promise<string | null | undefined>
  confirmDelete: (title: string) => Promise<boolean>
  onError?: (message: string) => void
  onSessionSwitched?: () => void
}

export function useProjectAiSessions(options: UseProjectAiSessionsOptions) {
  const sessions = ref<AiChatSession[]>([])
  const activeSessionId = ref('')
  const sessionSearch = ref('')
  const sessionSort = ref<SessionSortMode>('recent')

  const activeSession = computed(() => sessions.value.find((item) => item.id === activeSessionId.value) || null)
  const visibleSessions = computed(() => {
    const needle = sessionSearch.value.toLowerCase()
    const filtered = sessions.value.filter((session) => {
      if (!needle) return true
      return [session.title, ...session.messages.map((message) => message.text)].join(' ').toLowerCase().includes(needle)
    })
    return [...filtered].sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
      if (sessionSort.value === 'name') return a.title.localeCompare(b.title)
      return sessionSort.value === 'oldest' ? a.updatedAt - b.updatedAt : b.updatedAt - a.updatedAt
    })
  })

  function createMessageId() {
    return `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
  }

  function createSession(title = '新会话'): AiChatSession {
    return {
      id: createMessageId(),
      title,
      messages: [],
      updatedAt: Date.now(),
    }
  }

  function normalizeSession(session: AiChatSession): AiChatSession {
    return { ...session, updatedAt: session.updatedAt || Date.now(), pinned: Boolean(session.pinned) }
  }

  function touchSession(session: AiChatSession | null) {
    if (session) session.updatedAt = Date.now()
  }

  function hydrateSessions(rawState?: string) {
    if (!rawState) {
      const fresh = createSession()
      sessions.value = [fresh]
      activeSessionId.value = fresh.id
      return
    }
    try {
      const parsed = JSON.parse(rawState) as {
        sessions?: AiChatSession[]
        history?: Array<{ id?: string; role?: 'user' | 'assistant'; message?: string; text?: string; responseTime?: number }>
        title?: string
        activeSessionId?: string
        sessionSort?: SessionSortMode
      }
      if (parsed.sessions?.length) {
        sessions.value = parsed.sessions.map(normalizeSession)
        activeSessionId.value = parsed.activeSessionId && parsed.sessions.some((item) => item.id === parsed.activeSessionId)
          ? parsed.activeSessionId
          : parsed.sessions[0].id
        sessionSort.value = parsed.sessionSort || 'recent'
        return
      }
      if (parsed.history?.length) {
        const legacyMessages = parsed.history
          .filter((item) => item.role === 'user' || item.role === 'assistant')
          .map((item) => ({
            id: item.id || createMessageId(),
            role: item.role as 'user' | 'assistant',
            text: item.text || item.message || '',
            responseTime: item.responseTime,
          }))
        const fresh = createSession(parsed.title || '历史会话')
        fresh.messages = legacyMessages
        sessions.value = [fresh]
        activeSessionId.value = fresh.id
        return
      }
    } catch {
      // Rebuild local session state when persisted legacy payload is malformed.
    }
    const fresh = createSession()
    sessions.value = [fresh]
    activeSessionId.value = fresh.id
  }

  function buildSessionState() {
    return JSON.stringify({
      sessions: sessions.value,
      activeSessionId: activeSessionId.value,
      sessionSort: sessionSort.value,
    })
  }

  async function syncSessionState() {
    try {
      await options.persist(options.projectId.value, buildSessionState())
    } catch (err) {
      options.onError?.(err instanceof Error ? err.message : '同步会话状态失败')
    }
  }

  function ensureActiveSession() {
    if (!activeSession.value) {
      const fresh = createSession()
      sessions.value = [fresh]
      activeSessionId.value = fresh.id
    }
  }

  function switchSession(sessionId: string) {
    activeSessionId.value = sessionId
    options.onSessionSwitched?.()
    syncSessionState()
  }

  function newSession() {
    const fresh = createSession()
    sessions.value = [fresh, ...sessions.value]
    activeSessionId.value = fresh.id
    syncSessionState()
  }

  async function renameSession(sessionId: string) {
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) return
    const nextTitle = await options.promptTitle(session.title)
    if (!nextTitle?.trim()) return
    session.title = nextTitle.trim().slice(0, 40)
    touchSession(session)
    syncSessionState()
  }

  async function deleteSession(sessionId: string) {
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) return
    const confirmed = await options.confirmDelete(session.title)
    if (!confirmed) return
    sessions.value = sessions.value.filter((item) => item.id !== sessionId)
    if (!sessions.value.length) {
      const fresh = createSession()
      sessions.value = [fresh]
      activeSessionId.value = fresh.id
    } else if (activeSessionId.value === sessionId) {
      activeSessionId.value = visibleSessions.value[0]?.id || sessions.value[0].id
    }
    syncSessionState()
  }

  function togglePinSession(sessionId: string) {
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) return
    session.pinned = !session.pinned
    touchSession(session)
    syncSessionState()
  }

  return {
    sessions,
    activeSessionId,
    sessionSearch,
    sessionSort,
    activeSession,
    visibleSessions,
    createSession,
    createMessageId,
    touchSession,
    hydrateSessions,
    buildSessionState,
    syncSessionState,
    ensureActiveSession,
    switchSession,
    newSession,
    renameSession,
    deleteSession,
    togglePinSession,
  }
}
