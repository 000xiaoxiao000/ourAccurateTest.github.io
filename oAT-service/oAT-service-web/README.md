# oAT-service-web

`oAT-service-web` 是 ourAccurateTest 的后端主服务，基于 Spring Boot 构建，打包为可直接运行的 `war`。它提供项目、应用、版本、用例、仓库、代码图谱、验证基线、质量门禁、Git 影响分析和受控 AI 业务工具等 API；AI 生成能力由独立的 `ai-platform` 提供。

## 目录结构

```text
oAT-service-web/
├── mvnw
├── pom.xml
├── start.sh
└── src/main/
    ├── java/com/oAT/web/
    │   ├── api/             # API 响应组装和页面 payload 服务
    │   ├── collector/       # 采集源模型和健康状态
    │   ├── common/          # 通用工具、源码解析、压缩包处理
    │   ├── config/          # Spring、Tomcat、数据库和前端资源配置
    │   ├── control/         # Controller 和拦截器
    │   ├── coverage/        # 覆盖率报告解析
    │   ├── domain/          # 版本、用例、API 端点等领域服务
    │   ├── infra/           # Git 等基础设施适配
    │   ├── language/        # Java 源码解析和静态信息抽取
    │   ├── persistence/     # Repository 和持久化实体
    │   ├── service/         # 业务服务接口与实现
    │   └── verification/    # 智溯分析、图谱、质量门禁和影响分析
    └── resources/
        ├── application.yml
        └── db/
            ├── migration/   # Flyway 迁移脚本
            ├── postgresql/  # PostgreSQL 手工脚本
            └── mysql/       # 历史兼容脚本
```

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 项目与应用 | 登录注册、项目列表、成员、标签、应用设置和仓库配置 |
| 版本中心 | 分支、Commit、Git 拉取、版本创建、版本对比和报告详情 |
| 用例中心 | 用例目录、用例详情、导入导出、缺陷/PRD 链接 |
| API 端点分析 | 从源码包或 Git 缓存中识别 HTTP API 端点 |
| 代码图谱 | 应用视图、源码树、代码关系、追溯图和影响图 |
| 验证工作区 | 导入需求、用例、源码、执行报告、覆盖率等分析资产 |
| AI 一致性分析 | 基于验证基线生成追溯边、AI 发现和结构化分析结果 |
| 发现审核 | 人工审核 AI 发现、追溯边和外部回写链接 |
| 质量门禁 | 配置门禁策略、评估基线、记录豁免和 stale 状态 |
| Git 影响分析 | 基于 Git Diff 分析变更范围并辅助定位影响链路 |

## 覆盖率支持

后端覆盖率解析位于 `coverage/universal/`，统一转换为 `UniversalCoverageFile`。

| SourceType | 支持格式 |
| --- | --- |
| `JAVA` | JaCoCo XML、JaCoCo HTML |
| `FRONTEND` | Istanbul JSON |
| `CPP` | LCOV、gcov |
| `GO` | go cover、LCOV |
| `PYTHON` | coverage.py JSON、LCOV |

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21 | 运行时和编译目标 |
| Spring Boot 3.4.4 | Web 服务、配置、嵌入式 Tomcat |
| Spring JDBC | 数据访问 |
| PostgreSQL | 主数据库 |
| Flyway 10 | 数据库迁移 |
| JGit | Git 仓库访问 |
| JavaParser / ASM | Java 源码和字节码分析 |
| 多语言覆盖率解析 | JaCoCo、Istanbul、LCOV、gcov、go cover、coverage.py |
| LangChain4j | 在独立 `ai-platform` 中调用 LLM（经 `ai-platform-client` 接入） |

## 依赖服务

- PostgreSQL：保存结构化业务数据、验证结果和图谱数据。
- Git：用于仓库拉取、Commit 查询、Diff 和源码快照分析。
- AI 平台：独立项目 `ai-platform`（独立 git 仓库，`ai-platform-service` 提供任务/草稿/会话 API），业务经 `ai-platform-client` artifact 接入，通过 HTTP Tool Registry 读取业务资产并生成草稿。
- 本地文件目录：保存 Git 缓存、大载荷和源码压缩包。

## 配置

主配置文件：

```text
src/main/resources/application.yml
```

常用配置：

```yaml
server:
  port: 8899

spring:
  datasource:
    url: "${OAT_DB_URL:jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification}"
    username: "${OAT_DB_USERNAME:traceiq}"
    password: "${OAT_DB_PASSWORD:traceiq}"
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 1

oat:
  data:
    path: "${user.home}/oAT/codeData/"

ai-platform:
  base-url: "${OAT_AI_PLATFORM_URL:http://localhost:8898}"
  connect-timeout-ms: "${OAT_AI_PLATFORM_CONNECT_TIMEOUT_MS:2000}"
  read-timeout-ms: "${OAT_AI_PLATFORM_READ_TIMEOUT_MS:120000}"
  tenant-id: "${OAT_AI_PLATFORM_TENANT_ID:oAT}"
  tool-token: "${OAT_AI_TOOL_TOKEN:local-ai-tool-token}"
```

`oAT-service-web` 不在进程内调用模型。请先启动独立 `ai-platform-service`，并确保其
`AI_TOOL_GATEWAY_TOKEN` 与业务端 `OAT_AI_TOOL_TOKEN` 相同。

`oat.data.path` 需要有读写权限。上传限制默认是 `2048MB`，如果前面有 Nginx、网关或外部 Tomcat，也要同步调整请求体限制。

### 运行时日志级别

服务暴露 Spring Boot Actuator 的 `loggers` 端点，可在不停服的情况下调整当前进程日志级别：

```bash
curl -X POST http://localhost:8899/actuator/loggers/com.oAT \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}'
```

恢复为继承上级 logger：

```bash
curl -X POST http://localhost:8899/actuator/loggers/com.oAT \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel":null}'
```

该调整只对当前进程生效，服务重启后会回到 `application.yml` 或环境变量中的配置。

## 数据库迁移

服务启动时会通过 Flyway 自动执行 `src/main/resources/db/migration/`：

```text
V2__api_endpoint.sql
V5__normalized_core.sql
V6__ai_verification.sql
V7__traceability_gate.sql
V8__graph_facts.sql
V9__analysis_job_checkpoint.sql
V10__baseline_graph_versions_and_runtime_execution.sql
V11__partitioning_and_archive_markers.sql
V12__gate_enforcement_mode_and_stale_commit_view.sql
V13__git_impact_jobs.sql
V14__ai_generated_marker.sql
```

`src/main/resources/db/postgresql/` 和 `src/main/resources/db/mysql/` 保留为手工初始化或历史兼容脚本，默认运行路径以 Flyway `db/migration` 为准。

## 构建

从服务端聚合模块构建：

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml clean package
```

在本模块目录构建：

```bash
cd oAT-service/oAT-service-web
./mvnw -f ../pom.xml package
```

跳过测试：

```bash
./mvnw -f ../pom.xml package -DskipTests
```

构建产物：

```text
target/oAT-service-web-1.0.0-SNAPSHOT.war
```

## 启动

```bash
cd oAT-service/oAT-service-web
./start.sh
```

`start.sh` 会执行：

```bash
java --enable-native-access=ALL-UNNAMED -Dio.netty.noUnsafe=true -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

后台启动示例：

```bash
nohup ./start.sh > oat.log 2>&1 &
```

外部 Tomcat 部署时需使用 Tomcat 10+，以匹配 Spring Boot 3.x 的 Servlet 版本要求。

## API 前缀

主要前端接口使用 `/api` 前缀：

| 前缀 | 说明 |
| --- | --- |
| `/api/auth` | 登录、注册、登出、当前用户 |
| `/api/resource` | 临时资源上传 |
| `/api/projects` | 项目列表、项目上下文 |
| `/api/projects/{projectId}` | 项目设置、应用、成员、标签、版本、搜索 |
| `/api/projects/{projectId}/verification` | 验证工作区、基线、分析任务、追溯矩阵、质量门禁、影响分析 |
| `/api/projects/{projectId}/map` | 图谱首页、应用图谱、源码树和代码图 |

## 测试

```bash
cd oAT-service/oAT-service-web
./mvnw -f ../pom.xml test
```

当前测试覆盖验证图谱、质量门禁、影响分析、覆盖率解析和 AI 编排等后端逻辑。
