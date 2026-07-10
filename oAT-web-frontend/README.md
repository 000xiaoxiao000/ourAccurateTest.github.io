# oAT-web-frontend

`oAT-web-frontend` 是 oAccurateTest 的 Web 操作界面，基于 Vue 3、TypeScript 和 Vite 构建。它提供项目管理、应用配置、版本中心、用例中心、API 端点分析和 AI 对话等页面。

## 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5.x | UI 框架 |
| TypeScript | 5.9.x | 类型系统 |
| Vite | 7.x | 开发服务器和构建 |
| Vue Router | 4.x | 路由 |
| Pinia | 3.x | 状态管理 |

## 目录结构

```text
oAT-web-frontend/src/
├── api/          # HTTP 客户端、API 初始化、请求响应类型
├── components/   # 通用组件、图谱、用例、AI 浮动助手等
├── composables/  # Dialog、Toast 等组合式函数
├── layouts/      # 全局布局 AppShell
├── pages/        # 路由页面
├── router/       # 路由配置
├── stores/       # 登录态、当前项目等全局状态
└── utils/        # Markdown 等工具函数
```

## 主要页面

| 分类 | 页面能力 |
|---|---|
| 项目与应用 | 项目列表、项目首页、应用列表、成员、标签、应用设置、Git 仓库配置 |
| 采集源 | 采集源健康度 |
| 版本 | 版本列表、创建版本、关联应用、版本差异对比 |
| 用例 | 用例列表、详情、编辑、分享 |
| 分析 | API 端点分析、代码关系图谱、全局搜索、AI 智能分析 |
| 账号 | 登录、注册、账号设置 |

## 安装依赖

```bash
cd oAT-web-frontend
npm install
```

## 开发运行

```bash
npm run dev
```

默认地址：

```text
http://localhost:5173
```

开发环境 API 请求通过 `vite.config.ts` 中的 `server.proxy` 转发到后端，默认目标为 `http://localhost:8899`。如果需要经过 `oAT-relay`，把代理目标改为 `http://127.0.0.1:18089`。

## 类型检查与构建

```bash
npm run typecheck
npm run build
```

构建产物位于：

```text
dist/
```

本地预览生产构建：

```bash
npm run preview
```

## 后端集成

前端通过 `src/api/http.ts` 访问 `oAT-service-web`，接口统一使用 `/api/` 前缀。

后端地址配置位置：

| 环境 | 配置方式 |
|---|---|
| 开发 | `vite.config.ts` 的 `server.proxy` |
| 生产 | Nginx/网关反向代理，或由 `oAT-service-web` 托管静态资源 |

生产部署时，可将 `dist/` 内容交给 Nginx 等 Web 服务器，也可放入后端静态资源目录随 `oAT-service-web` 一起发布。

## 注意事项

- 建议使用 Node.js 18+。
- `npm run build` 会先执行 `vue-tsc --noEmit`，类型错误会导致构建失败。
- 登录 Token 存储在 `localStorage`，由 `stores/auth.ts` 管理刷新后的会话恢复。
