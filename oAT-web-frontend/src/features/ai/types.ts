export type AiSessionMessage = { id: string; role: 'user' | 'assistant'; text: string; responseTime?: number }
export type AiChatSession = { id: string; title: string; messages: AiSessionMessage[]; updatedAt: number; pinned?: boolean }
export type AiQuestionAnchor = { id: string; label: string; question: string; answered: boolean; responseTime: number; responseTimeText: string; shareUrl: string; messageId: string }
export type AiMessageSection = { id: string; anchorId: string; message: AiSessionMessage; startsQuestion: boolean; endsAnswer: boolean }
export type AiTimelineItem = {
  id: string
  anchorId: string
  label: string
  question: string
}

import type { AIAction } from '@/api/types'

export type AiFloatingMessage = {
  id: string
  role: 'user' | 'assistant'
  text: string
  suggestions?: string[]
  actions?: AIAction[]
}

export type AiFloatingContextChipId = 'route' | 'apps' | 'online' | 'topic' | 'image' | 'filter' | 'hover' | 'selection'

export type AiFloatingContextChip = {
  id: AiFloatingContextChipId
  label: string
  value: string
}

export type AiFeedbackType = 'helpful' | 'not_helpful' | 'incorrect' | 'incomplete'

export type AiFloatingPosition = { left: number; top: number }
export type AiFloatingPanelSize = { width: number; height: number }
export type AiFloatingSectionName = 'messages' | 'links' | 'starters'
export type AiFloatingLayoutItemName = 'messages' | 'context' | 'links' | 'starters' | 'compose'
export type AiFloatingLayoutRect = { left: number; top: number; width: number; height: number }
export type AiFloatingLayoutBounds = { left: number; top: number; width: number; height: number }
export type AiFloatingLayoutResizeDirection = 'n' | 'e' | 's' | 'w' | 'ne' | 'nw' | 'se' | 'sw'
