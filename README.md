# ourAccurateTest

`ourAccurateTest` 是面向研发、测试和质量保障团队的 AI 需求一致性验证平台。项目将需求、用例、源码、版本变更、运行证据和覆盖率数据组织为可追溯关系，并通过静态分析、运行证据投影和 LLM 辅助发现覆盖缺口、实现偏差和质量门禁风险。

> 本项目仅供个人学习、技术研究与交流使用。请在使用前确认第三方组件、代码仓库、流量采集对象和模型服务的授权，不要将生产密钥、证书或敏感流量提交到代码仓库。

## 模块总览

```text
ourAccurateTest/
├── oAT-service/
│   ├── oAT-ai/                 # LLM 接入模块，jar
│   ├── oAT-service-web/        # Spring Boot 后端主服务，war
│   └── pom.xml                 # 服务端 Maven 聚合构建
├── oAT-web-frontend/           # 平台 Web 前端
├── oAT-traffic-capture/        # Electron 桌面流量采集器
└── docs/                       # 文档图片和交流资源
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
        └── oAT-ai：LLM 配置和文本生成能力

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
export AI_LLM_API_KEY='your-api-key'
export AI_LLM_MODEL='deepseek-chat'
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

### 3. 启动平台 Web 前端

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

### 4. 启动桌面流量采集器

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
| `oAT-web-frontend` | `npm run dev` | 启动平台 Web 前端 |
| `oAT-web-frontend` | `npm run build` | 类型检查并构建 Web 前端 |
| `oAT-traffic-capture` | `npm run dev` | 启动 Electron 采集器开发模式 |
| `oAT-traffic-capture` | `npm run build` | 构建采集器前端和主进程 |
| `oAT-traffic-capture` | `npm run dist` | 生成桌面安装包 |

## 配置入口

| 配置 | 文件 |
| --- | --- |
| 后端端口、数据库、Flyway、LLM、本地存储 | `oAT-service/oAT-service-web/src/main/resources/application.properties` |
| Web 前端代理和端口 | `oAT-web-frontend/vite.config.ts` |
| 流量采集器 Electron 打包 | `oAT-traffic-capture/package.json` |
| 流量采集器 Vite 端口 | `oAT-traffic-capture/vite.config.ts` |

## 模块文档

- [服务端聚合模块](oAT-service/README.md)
- [后端主服务](oAT-service/oAT-service-web/README.md)
- [LLM 模块](oAT-service/oAT-ai/README.md)
- [平台 Web 前端](oAT-web-frontend/README.md)
- [桌面流量采集器](oAT-traffic-capture/README.md)

## 交流与反馈

项目交流资源位于 `docs/assets/`：

| 添加作者微信 | 微信交流入口 | AI + 精准测试实战交流群 |
| --- | --- | --- |
| <img src="docs/assets/wechat-friend.png" alt="添加作者微信二维码" width="220"> | <img src="docs/assets/wechat-contact.jpg" alt="微信交流二维码" width="220"> | <img src="docs/assets/ai-testing-group.png" alt="AI + 精准测试实战交流群二维码" width="220"> |
