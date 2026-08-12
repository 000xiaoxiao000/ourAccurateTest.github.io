# ourAccurateTest

`ourAccurateTest` 是服务于研发与质量团队的智溯平台。“智溯”代表智能分析与全链路追溯，项目将需求、用例、源码、版本变更、运行证据和覆盖率数据组织为可追溯关系，并通过静态分析、运行证据投影和 LLM 辅助发现覆盖缺口、实现偏差和质量门禁风险。

> 本项目仅供个人学习、技术研究与交流使用。请在使用前确认第三方组件、代码仓库、流量采集对象和模型服务的授权，不要将生产密钥、证书或敏感流量提交到代码仓库。

## 模块总览

```text
ourAccurateTest/
├── oAT-service/
│   ├── oAT-service-web/        # Spring Boot 业务主服务，war
│   └── pom.xml                 # 服务端 Maven 聚合构建
├── oAT-web-frontend/           # 平台 Web 前端
├── oAT-traffic-capture/        # Electron 桌面流量采集器
└── docs/                       # 文档图片和交流资源

独立 AI 平台（独立 git 仓库，见 https://github.com/your-org/ai-platform 或本地 ai-platform/）：
├── ai-platform-service/        # 独立 AI 服务，jar（端口 8898，schema ai_platform）
└── ai-platform-client/         # 业务侧薄客户端 artifact（Maven: com.aiplatform:ai-platform-client）
```

## 系统组成

```text
oAT-web-frontend
        │ /api
        ▼
oAT-service-web
        ├── PostgreSQL + Flyway：项目、应用、版本、用例、验证基线、追溯和门禁数据
        ├── Git：仓库、分支、Commit、Diff 和源码快照
        ├── 本地文件目录：源码缓存、大载荷和静态源码内容
        └── /api/ai-tools：向 ai-platform 暴露受控业务工具（经 ai-platform-client 接入）

ai-platform（独立项目 ai-platform/，业务零 AI 代码）
        ├── LangChain4j + DeepSeek/OpenAI 兼容模型：生成结构化 AI 草稿
        ├── ai_platform schema：保存任务、草稿、会话、确认状态
        ├── HTTP Tool Registry：读取业务资产，后续可平滑替换为 MCP
        └── /admin 管理台：任务监控、草稿管理、会话记忆、配置与接入（X-Admin-Token 鉴权）

oAT-traffic-capture
        ├── 本地代理：HTTP/HTTPS/WS/WSS 流量采集
        ├── SQLite：历史会话、记录和过滤规则
        └── 插件：捕获后与保存前流量处理
```

## 核心能力

- 验证工作区：导入需求、用例、源码、执行报告、覆盖率和 Git 源码快照。
- 验证基线：以一组资产快照建立分析基线，保存证据、状态和分析结果。
- 一致性分析：结合 LLM 与结构化证据生成追溯边、风险发现和分析摘要。
- 追溯矩阵与图谱：查看需求、用例、源码、运行证据、覆盖率和 AI 发现之间的关系。
- 质量门禁：配置门禁策略、评估基线结果、记录豁免并识别 stale 状态。
- Git 影响分析：基于 Commit/Diff 分析变更范围并联动图谱过期标记。
- 代码与 API 分析：解析 Java 源码、字节码和接口端点，支撑版本对比与影响定位。
- 多语言覆盖率解析：支持 JaCoCo、Istanbul、LCOV、gcov、go cover 和 coverage.py。
- 桌面流量采集：采集、过滤、查看、重放和导出 HTTP/HTTPS、WebSocket、MQTT 等流量。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.4、Spring JDBC、Druid |
| AI | LangChain4j 1.12.2、OpenAI 兼容接口、DeepSeek、Ollama |
| 数据库 | PostgreSQL、Flyway |
| 源码与覆盖率 | JGit、JavaParser、ASM、JaCoCo、Istanbul、LCOV、gcov、go cover、coverage.py |
| Web 前端 | Vue 3、TypeScript、Vite 7、Vue Router、Pinia |
| 流量采集器 | Electron 30、Vue 3、Vite 5、http-mitm-proxy、better-sqlite3、ws、mqtt、ExcelJS |

## 环境要求

- JDK 17+
- Node.js 18+
- PostgreSQL 12+，建议使用仍在维护的版本
- 可访问的 Git 仓库
- 可选：Maven 3.8+；仓库内提供 `oAT-service/oAT-service-web/mvnw`
- 可选：OpenAI、DeepSeek、Ollama 或兼容 OpenAI 协议的模型服务
- 可选：macOS 证书信任权限，用于桌面采集器解密 HTTPS/WSS

## 快速开始

### 1. 配置后端

后端默认端口是 `8899`，默认数据库是 PostgreSQL：

```bash
export OAT_DB_URL='jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification'
export OAT_DB_USERNAME='traceiq'
export OAT_DB_PASSWORD='change-me'
```

数据库迁移由 Flyway 自动执行：

```text
oAT-service/oAT-service-web/src/main/resources/db/migration/
```

后端本地数据目录默认是：

```text
${user.home}/oAT/codeData/
```

### 2. 构建并启动后端

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml clean package -DskipTests

cd oAT-service-web
./start.sh
```

后端启动后监听：

```text
http://localhost:8899
```

### 3. 启动独立 AI 平台（ai-platform，独立项目）

AI 平台已拆分为独立 git 仓库（本地路径 `../ai-platform/`）。业务通过 `ai-platform-client` 接入，本仓库业务零 AI 代码。

```bash
cd ../ai-platform
export AI_PLATFORM_API_KEY='your-api-key'          # 模型密钥（必填，否则 AI 走降级）
export AI_PLATFORM_ADMIN_TOKEN='your-admin-token'  # 管理台 /admin 鉴权（可选）
export AI_TOOL_GATEWAY_TOKEN='local-ai-tool-token' # 与业务端 OAT_AI_TOOL_TOKEN 保持一致
export OAT_DB_URL='jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification'
export OAT_DB_USERNAME='traceiq'
export OAT_DB_PASSWORD='change-me'
java -jar ai-platform-service/target/ai-platform-service-0.1.0-SNAPSHOT.jar
```

AI 平台监听 `http://localhost:8898`，管理台 `http://localhost:8898/admin`（任务监控 / 草稿管理 / 会话记忆 / 配置与接入，需 `X-Admin-Token`）。

### 4. 启动平台 Web 前端

```bash
cd oAT-web-frontend
npm install
npm run dev
```

开发地址：

```text
http://localhost:5176
```

前端开发服务器默认代理到 `http://localhost:8899`。如需修改：

```bash
OAT_BACKEND_TARGET='http://127.0.0.1:8899' npm run dev
```

### 5. 启动桌面流量采集器

```bash
cd oAT-traffic-capture
npm install
npm run dev
```

采集器渲染进程开发端口是 `5173`，本地代理默认端口是 `8888`，代理端口可在应用内调整。

## 常用命令

| 目录 | 命令 | 说明 |
| --- | --- | --- |
| `oAT-service` | `./oAT-service-web/mvnw -f pom.xml test` | 运行服务端测试 |
| `oAT-service` | `./oAT-service-web/mvnw -f pom.xml clean package -DskipTests` | 打包服务端 |
| `../ai-platform` | `mvn -f pom.xml package` | 打包独立 AI 平台（service + client） |
| `../ai-platform` | `java -jar ai-platform-service/target/ai-platform-service-0.1.0-SNAPSHOT.jar` | 启动独立 AI 平台 |
| `oAT-web-frontend` | `npm run dev` | 启动平台 Web 前端 |
| `oAT-web-frontend` | `npm run build` | 类型检查并构建 Web 前端 |
| `oAT-traffic-capture` | `npm run dev` | 启动 Electron 采集器开发模式 |
| `oAT-traffic-capture` | `npm run build` | 构建采集器前端和主进程 |
| `oAT-traffic-capture` | `npm run dist` | 生成桌面安装包 |

## 配置入口

| 配置 | 文件 |
| --- | --- |
| 后端端口、数据库、Flyway、LLM、本地存储 | `oAT-service/oAT-service-web/src/main/resources/application.yml` |
| AI 平台（端口 8898、模型、管理台 Token、业务回调） | `../ai-platform/ai-platform-service/src/main/resources/application.yml` |
| Web 前端代理和端口 | `oAT-web-frontend/vite.config.ts` |
| 流量采集器 Electron 打包 | `oAT-traffic-capture/package.json` |
| 流量采集器 Vite 端口 | `oAT-traffic-capture/vite.config.ts` |

## 模块文档

- [服务端聚合模块](oAT-service/README.md)
- [后端主服务](oAT-service/oAT-service-web/README.md)
- [独立 AI 平台](../ai-platform/README.md)（独立仓库：ai-platform-service / ai-platform-client）
- [平台 Web 前端](oAT-web-frontend/README.md)
- [桌面流量采集器](oAT-traffic-capture/README.md)

## GitHub Pages

项目站点位于 `docs/`，通过 `.github/workflows/pages.yml` 在推送到 `master` 或 `main` 分支时自动部署。

仓库地址：

```text
https://github.com/000xiaoxiao000/ourAccurateTest.github.io.git
```

在 GitHub 仓库的 `Settings -> Pages` 中，将发布来源设置为 `GitHub Actions`。部署完成后，项目站点通常访问：

```text
https://000xiaoxiao000.github.io/ourAccurateTest.github.io/
```

## 交流与反馈

项目交流资源位于 `docs/assets/`：

| 添加作者微信 | 微信公众号入口 | AI + 精准测试实战交流群 |
| --- | --- | --- |
| <img src="docs/assets/wechat-friend.png" alt="添加作者微信二维码" width="220"> | <img src="docs/assets/wechat-contact.jpg" alt="微信公众号二维码" width="220"> | <img src="docs/assets/ai-testing-group.png" alt="AI + 精准测试实战交流群二维码" width="220"> |
