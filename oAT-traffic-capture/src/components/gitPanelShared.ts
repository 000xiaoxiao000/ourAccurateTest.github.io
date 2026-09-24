import { reactive } from 'vue'
import type { GitRefItem } from '../types/electron'

/**
 * 新版 / 旧版源码目录参数下各挂一个 GitSourcePanel 实例。
 * 连接信息（来源、仓库地址、鉴权、已加载的分支/Tag）跨实例共享——
 * 用户在新版面板填过一次地址和密码，切到旧版面板不用再填。
 * ref 选取（分支/提交/手填）与「检出到」落点是每个面板自己的事，仍各自独立。
 */
export const gitShared = reactive({
  source: 'local' as 'local' | 'remote',
  repoPath: '',
  url: '',
  authMode: 'system' as 'system' | 'password' | 'token' | 'ssh',
  username: '',
  secret: '',
  sshKeyPath: '',
  allowInsecureSsl: false,
  branches: [] as GitRefItem[],
  tags: [] as GitRefItem[]
})
