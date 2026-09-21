import fs from 'fs'

/**
 * classfiles（覆盖率分母）= 仅本地路径。
 * 绝不从运行容器 classdumpdir 拿；如需从构建侧/镜像仓库获取，请先落到本地路径再填写。
 */
export async function resolveClassfiles(localPath: string): Promise<{ success: boolean; resolvedPath?: string; error?: string }> {
  try {
    // 支持多路径（多模块）以 ';' 分隔：目录、zip、jar 均可（CLI 侧按 entry 分析）
    const parts = (localPath || '').split(';').map((s) => s.trim()).filter(Boolean)
    if (parts.length === 0) {
      return { success: false, error: '未填写 classfiles 本地路径（可用「自动推导」从项目根目录生成）' }
    }
    for (const p of parts) {
      if (!fs.existsSync(p)) {
        return { success: false, error: `本地 classfiles 不存在: ${p}` }
      }
    }
    return { success: true, resolvedPath: parts.join(';') }
  } catch (e: any) {
    return { success: false, error: e?.message ?? String(e) }
  }
}
