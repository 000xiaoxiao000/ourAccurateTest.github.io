import fs from 'fs'

/**
 * classfiles（覆盖率分母）= 仅本地路径。
 * 绝不从运行容器 classdumpdir 拿；如需从构建侧/镜像仓库获取，请先落到本地路径再填写。
 */
export async function resolveClassfiles(localPath: string): Promise<{ success: boolean; resolvedPath?: string; error?: string }> {
  try {
    if (!localPath || !fs.existsSync(localPath)) {
      return { success: false, error: `本地 classfiles 不存在: ${localPath || ''}` }
    }
    return { success: true, resolvedPath: localPath }
  } catch (e: any) {
    return { success: false, error: e?.message ?? String(e) }
  }
}
