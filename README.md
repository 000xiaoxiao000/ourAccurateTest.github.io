# ourAccurateTest

`ourAccurateTest` 是一个面向研发、测试和质量保障团队的需求一致性验证平台。平台将需求、测试用例、源码、Git 变更和运行证据组织为可追溯的数据关系，并通过静态分析与大语言模型辅助发现覆盖缺口、实现偏差和质量风险。

> 本项目仅供个人学习、技术研究与交流使用。请在使用前确认第三方组件、代码仓库和模型服务的授权，并不要将生产密钥直接提交到配置文件或代码仓库。

## 功能概览

- **验证工作区**：导入需求、用例、源码、执行报告和覆盖率等分析资产。
- **验证基线**：以一组外部资产快照建立分析基线，记录证据和分析结果。
- **一致性分析**：结合静态源码分析和 LLM，检查需求、验收条件、用例与代码之间的关系。
- **追溯矩阵**：查看需求、用例、源码和运行证据之间的追溯边。
- **发现审核**：对 AI 发现进行人工确认、驳回和补充说明。
- **质量门禁**：配置质量策略，评估基线结果并记录豁免。
- **Git 变更影响**：拉取仓库、查看 Commit/Diff，并分析代码变更对验证范围的影响。
- **代码关系图谱**：浏览应用、源码树、API 端点和影响关系。
- **项目管理**：管理项目、应用、成员、标签、仓库配置和版本。

## 项目结构

```text
ourAccurateTest/
├── oAT-service/
│   ├── oAT-ai/                 # LLM 配置与调用模块，打包为 jar
│   ├── oAT-service-web/        # Spring Boot 后端主服务，打包为 war
│   └── pom.xml                 # 服务端 Maven 聚合构建
├── oAT-web-frontend/           # Vue 3 + TypeScript Web 前端
└── docs/                       # 项目文档与交流二维码等资源
```

## 系统组成

```text
Web 前端（Vue 3 / Vite）
        │ /api
        ▼
后端主服务（Spring Boot）
        ├── PostgreSQL：项目、应用、版本、验证基线和分析结果
        ├── Git：仓库、分支、Commit、Diff 和源码快照
        ├── 本地文件目录：源码缓存和大载荷
        └── oAT-ai：LLM 调用与 AI 分析
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.4、Spring JDBC |
| AI | LangChain4j 1.12.2、OpenAI 兼容接口、Ollama |
| 数据库 | PostgreSQL、Flyway |
| 源码分析 | JGit、JavaParser、ASM、JaCoCo |
| 前端 | Vue 3、TypeScript、Vite 7、Vue Router、Pinia |

## 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- PostgreSQL 12+（建议使用受支持的较新版本）
- 可访问的 Git 仓库
- 可选：OpenAI、DeepSeek 或其他 OpenAI 兼容模型服务；也可以使用本地 Ollama

## 快速开始

### 1. 配置数据库和模型

后端默认监听 `8899`，默认数据库配置如下：

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification
spring.datasource.username=traceiq
spring.datasource.password=traceiq
```

建议通过环境变量覆盖默认值：

```bash
export OAT_DB_URL='jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification'
export OAT_DB_USERNAME='traceiq'
export OAT_DB_PASSWORD='change-me'
export AI_LLM_API_KEY='your-api-key'
export AI_LLM_MODEL='deepseek-chat'
```

数据库迁移由 Flyway 自动执行，迁移文件位于：

```text
oAT-service/oAT-service-web/src/main/resources/db/migration/
```

后端运行时会在 `oat.data.path` 指定的位置保存 Git 源码缓存和大载荷，默认路径为：

```text
${user.home}/oAT/codeData/
```

### 2. 构建后端

推荐从服务端聚合模块构建：

```bash
cd oAT-service
mvn clean install
```

仅构建后端主服务时：

```bash
cd oAT-service/oAT-service-web
./mvnw -f ../pom.xml package -DskipTests
```

启动后端：

```bash
./start.sh
```

构建产物为：

```text
oAT-service/oAT-service-web/target/oAT-service-web-1.0.0-SNAPSHOT.war
```

### 3. 启动前端

```bash
cd oAT-web-frontend
npm install
npm run dev
```

开发地址：

```text
http://localhost:5176
```

开发服务器会将 `/api` 等请求代理到 `http://localhost:8899`。如需修改后端地址：

```bash
OAT_BACKEND_TARGET='http://127.0.0.1:8899' npm run dev
```

登录后即可从项目列表进入验证工作区、版本中心和代码关系图谱。

## 前端命令

```bash
npm run dev        # 启动开发服务器
npm run typecheck  # 执行 Vue/TypeScript 类型检查
npm run build      # 类型检查并构建生产资源
npm run preview    # 预览生产构建
```

生产构建产物位于 `oAT-web-frontend/dist/`，可由 Nginx 等 Web 服务器托管，并将 API 请求反向代理到后端 `8899` 端口。

## 配置参考

后端主配置文件：

```text
oAT-service/oAT-service-web/src/main/resources/application.properties
```

常用配置：

| 配置 | 说明 |
| --- | --- |
| `server.port` | 后端端口，默认 `8899` |
| `OAT_DB_URL` | PostgreSQL JDBC 地址 |
| `OAT_DB_USERNAME` / `OAT_DB_PASSWORD` | 数据库账号和密码 |
| `oat.data.path` | Git 源码缓存和文件存储目录 |
| `AI_LLM_API_KEY` | 模型服务 API Key |
| `AI_LLM_MODEL` | 模型名称 |
| `ai.llm.provider` | `openai`、`deepseek`、`ollama` 或 `custom` |

上传请求默认允许单文件和单次请求最大 `2048MB`。如果使用 Nginx、网关或外部 Tomcat，需要同步调整请求体大小限制。

## 模块文档

- [服务端聚合模块](oAT-service/README.md)
- [后端主服务](oAT-service/oAT-service-web/README.md)
- [LLM 模块](oAT-service/oAT-ai/README.md)
- [Web 前端](oAT-web-frontend/README.md)

## 交流与反馈

项目交流资源位于 `docs/assets/`：

| 添加作者微信 | 微信交流入口 | AI + 精准测试实战交流群 |
| --- | --- | --- |
| <img src="docs/assets/wechat-friend.png" alt="添加作者微信二维码" width="220"> | <img src="docs/assets/wechat-contact.jpg" alt="微信交流二维码" width="220"> | <img src="docs/assets/ai-testing-group.png" alt="AI + 精准测试实战交流群二维码" width="220"> |
