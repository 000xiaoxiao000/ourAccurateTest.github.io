# src/shared/api

`src/shared/api` 预留给跨业务域复用的 API 客户端、请求类型和响应类型。

当前项目的主要 API 仍集中在 `src/api/`：

- `http.ts`：统一请求封装
- `types.ts`：通用类型
- `bootstrap.ts`：登录、项目和初始化数据
- `version.ts`：版本中心和 Git 工作流
- `verification.ts`：验证工作区、追溯矩阵、发现审核、质量门禁
- `graph.ts` / `traceabilityMap.ts`：图谱和追溯图接口

## 放置规则

- 只服务单个页面或单个业务域的接口，优先放在 `src/api/` 或对应 feature 附近。
- 被多个业务域共享、且不天然属于某个现有 API 文件的接口，可以放到本目录。
- 新增请求必须复用 `src/api/http.ts`，不要在页面或组件中直接散落 `fetch` 调用。
- 新增类型优先使用明确的 request/response 命名，避免把后端原始字段直接扩散到多个页面。

