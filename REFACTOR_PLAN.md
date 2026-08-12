# ourAccurateTest 重构执行计划（Codex 可执行版）

> **用途**：本文件是让 AI 编码代理（OpenAI Codex / Claude Code 等）执行的**唯一重构依据**。
> **执行方式**：在项目根目录运行 `codex exec --full-auto -f REFACTOR_PLAN.md`，或让代理逐 Task 按本文档执行。
> **目标**：把 AI 从「业务内嵌」重构为「独立 AI 平台（ai-platform）+ MCP 协议 + 双轨人在回路」。
> **总工期**：约 4.5 周。本文档中所有路径均相对项目根 `/Users/xiaoxiao/personal-JavaProject/ourAccurateTest/`。

---

## 0. 执行总则（先读，再动手）

### 0.1 最终架构（一句话）

新增独立服务模块 `oAT-service/ai-platform`（端口 **8898**、独立数据库 schema `ai_platform`），负责 LLM 编排、草稿库、观测；业务模块 `oAT-service/oAT-service-web`（端口 **8899**）把能力暴露为 MCP Server，并**完整保留全部手动路径**；前端 `oAT-web-frontend` 增加「AI 辅助」按钮 + 草稿预览/确认组件。**AI 产出只进草稿库，人确认后才落业务库。**

### 0.2 硬性规则（DO / DO NOT）

**DO：**
- 严格按 Phase 顺序执行；每个 Task 完成后先跑「验收」，再进入下一个 Task。
- 改代码前先读相关文件；尤其 `VerificationAiOrchestrator.java`（约 1512 行）**先通读再动手**。
- 任何删除操作前先用 IDE/`grep` Find Usages 确认无引用。
- 每个 Task 完成后提交一次 git，commit message 含 Task 编号（如 `refactor: T1.2 ai-platform config`）。

**DO NOT：**
- ❌ 不要删除/改动 9 个覆盖率解析器、JavaParser、ASM、JGit 相关代码（确定性分析，保留不动）。
- ❌ 不要修改任何已存在的 Flyway 迁移文件（`V1~V13`），只允许**新增** `V14+`（业务 schema）或 `V1+`（ai_platform schema）。
- ❌ 不要在 yaml / 代码 / 日志中写死 API Key，一律环境变量 `OAT_AI_API_KEY`。
- ❌ 不要手动同步数据库表结构，一律用 Flyway 迁移。
- ❌ 不要一次性删除 `VerificationAiOrchestrator` 全部内容，按 Task 分步迁移、灰度确认后再清理。

### 0.3 关键路径速查

| 用途 | 路径 |
|---|---|
| 父 POM | `oAT-service/pom.xml` |
| 现有 AI 模块 | `oAT-service/oAT-ai/` |
| 业务模块 | `oAT-service/oAT-service-web/` |
| AI 编排核心（将被拆解） | `oAT-service/oAT-service-web/src/main/java/com/oAT/web/verification/VerificationAiOrchestrator.java` |
| 现有 AI 配置（apiKey 明文所在） | `oAT-service/oAT-ai/src/main/java/com/oAT/ai/config/AIConfig.java` |
| 业务 DB 迁移目录（最新 V13） | `oAT-service/oAT-service-web/src/main/resources/db/migration/` |
| 前端 API 层 | `oAT-web-frontend/src/api/` |
| 前端页面 | `oAT-web-frontend/src/pages/` |
| 启动脚本 | `scripts/start-backend.sh` |

### 0.4 端口 / 依赖规划

| 服务 | 端口 | 说明 |
|---|---|---|
| ai-platform（新增） | 8898 | 独立进程、独立 schema `ai_platform` |
| oAT-service-web | 8899 | 现有，不动 |
| 前端 dev server | 5176 | 现有，不动 |
| PostgreSQL | 5432 | 同一实例，ai-platform 用独立 schema（flyway 从 V1 起，与业务互不冲突） |

### 0.5 构建与启动命令

```bash
# 后端全量编译
mvn -f oAT-service/pom.xml -q clean compile

# 单模块构建（ai-platform 及其依赖）
mvn -f oAT-service/pom.xml -pl ai-platform -am package

# 启动业务后端（优先用现有脚本）
bash scripts/start-backend.sh

# 启动 ai-platform（独立进程）
java -jar oAT-service/ai-platform/target/ai-platform-0.1.0-SNAPSHOT.jar

# 前端开发
cd oAT-web-frontend && npm install && npm run dev

# 前端类型检查 + 构建
cd oAT-web-frontend && npx vue-tsc --noEmit && npx vite build
```

---

## Phase 1：ai-platform 平台骨架（约 1 周）

> 目标：跑通「提交任务 → Agent 执行 → 草稿产出」最小闭环，**不依赖任何业务**（先用内置 echo/规则工具验证）。

### T1.1 新建 ai-platform Maven 模块

**涉及文件：**
- 新增 `oAT-service/ai-platform/pom.xml`
- 修改 `oAT-service/pom.xml`（`<modules>` 增加 `ai-platform`）

**实现要点：**
1. `ai-platform/pom.xml`：parent 指向 `oAT-service/pom.xml`；依赖：
   - `spring-boot-starter-web`
   - `spring-boot-starter-jdbc`（或 `spring-boot-starter-data-jpa`，二选一，推荐 JDBC + JdbcTemplate 保持轻量）
   - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
   - `org.postgresql:postgresql`
   - `dev.langchain4j:langchain4j`（版本与 oAT-ai 现有一致，先查 `oAT-ai/pom.xml` 确认版本号）
   - `dev.langchain4j:langchain4j-deepseek`（若存在该 artifact；否则用 OpenAI 兼容端点配置 DeepSeek，以 oAT-ai 现有用法为准）
   - 可选：`dev.langchain4j:langchain4j-langfuse`（可观测，可后置）
   - `spring-boot-maven-plugin` 打包成可执行 jar
2. 新建主类 `com.oAT.aiplatform.AiPlatformApplication`（`@SpringBootApplication`），默认包扫描 `com.oAT.aiplatform`。

**验收：** `mvn -f oAT-service/pom.xml -pl ai-platform -am package` 构建成功，产出可执行 jar。

### T1.2 配置与密钥

**涉及文件：**
- 新增 `oAT-service/ai-platform/src/main/resources/application.yml`

**实现要点：**
1. `server.port: 8898`
2. `spring.datasource.url` 指向现有 PostgreSQL（从 `oAT-service-web` 的 yaml 复制连接串，库名保持一致）
3. `spring.flyway.schemas: ai_platform`（独立 schema）
4. DeepSeek 配置：`OAT_AI_API_KEY` 环境变量注入，**禁止明文**：
   ```yaml
   ai:
     api-key: ${OAT_AI_API_KEY:}
     base-url: https://api.deepseek.com
     model: deepseek-chat
   ```
5. `spring.datasource` 密码同样走环境变量（沿用业务现有做法）。

**验收：** `grep -rn "sk-" oAT-service/ai-platform/src/main/resources/ || echo "无明文密钥"` 无输出。

### T1.3 草稿库（独立 schema + Flyway V1）

**涉及文件：**
- 新增 `oAT-service/ai-platform/src/main/resources/db/migration/V1__ai_draft.sql`

**实现要点：**
```sql
CREATE SCHEMA IF NOT EXISTS ai_platform;
CREATE TABLE ai_platform.ai_draft (
    id           BIGSERIAL PRIMARY KEY,
    task_id      VARCHAR(64)  NOT NULL UNIQUE,
    biz          VARCHAR(64)  NOT NULL,          -- 业务标识（如 oAT）
    intent       VARCHAR(128) NOT NULL,          -- 意图（如 requirement.parse）
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/CONFIRMED/REJECTED
    payload      JSONB,                          -- AI 产出草稿
    error        TEXT,
    ai_generated BOOLEAN NOT NULL DEFAULT TRUE,  -- 追溯标注
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ
);
CREATE INDEX idx_ai_draft_biz ON ai_platform.ai_draft (biz, created_at DESC);
```

**验收：** 启动后 `psql -c "\dt ai_platform.*"` 能查到 `ai_draft` 表。

### T1.4 领域模型

**涉及文件：** 新增 `com.oAT.aiplatform.model.AiTaskRequest`、`com.oAT.aiplatform.model.AiDraft`（`AiDraft` 与表字段一一对应，payload 用 `String` 存 JSON）。

**实现要点：** `AiTaskRequest` 字段：`biz`、`intent`、`context`（`Map<String,Object>`）；提供静态工厂/转换方法。

**验收：** 编译通过。

### T1.5 DeepSeek 客户端与降级

**涉及文件：**
- 新增 `com.oAT.aiplatform.llm.ModelClient`
- 新增 `com.oAT.aiplatform.llm.GracefulFallback`

**实现要点：**
1. `ModelClient` 封装 LangChain4j 的 `ChatLanguageModel`（DeepSeek）；方法 `String generate(String system, String user)`。
2. 失败重试：最多 2 次（间隔 1s、2s）。
3. 仍失败 → `GracefulFallback` 返回结构化错误（如 `{"fallback":true,"message":"AI 服务暂不可用"}`），**不抛异常导致接口 500**。
4. 预留 `FallbackHandler` 接口，供后续接规则化分析。

**验收：** 注入错误 API Key 启动，调用不崩溃、返回 fallback JSON。

### T1.6 AgentRunner

**涉及文件：** 新增 `com.oAT.aiplatform.agent.AgentRunner`

**实现要点：**
1. 先实现「意图路由」：`Map<String, AgentHandler>`，内置一个 `echo` handler（返回 context 原样）用于链路验证。
2. `String submit(AiTaskRequest req)`：创建 taskId（UUID）、落库 PENDING、提交到 `TaskExecutor` 异步执行。
3. 异步执行：`@Async` + 配置线程池（核心 4 / 最大 8），执行中更新 RUNNING，完成写 payload 置 DONE，异常置 FAILED + error。
4. Phase 1 阶段不接 MCP，后续 Phase 2 在 `AgentHandler` 里接 MCP 工具调用。

**验收：** 提交 echo 任务，轮询后 draft 返回 context 原样。

### T1.7 任务接口（REST）

**涉及文件：** 新增 `com.oAT.aiplatform.api.AiTaskController`

**实现要点：**
```java
@PostMapping("/api/ai/tasks")              // body: AiTaskRequest -> 返回 taskId
@GetMapping("/api/ai/tasks/{taskId}")     // 返回状态 AiDraft
@GetMapping("/api/ai/tasks/{taskId}/draft") // 返回草稿 payload
@PostMapping("/api/ai/tasks/{taskId}/confirm") // body: {confirmed: true|false} -> 置 CONFIRMED/REJECTED
```

**验收（Phase 1 完成判定）：**
```bash
mvn -f oAT-service/pom.xml -pl ai-platform -am package
# 启动 ai-platform 后：
curl -s -X POST http://localhost:8898/api/ai/tasks \
  -H 'Content-Type: application/json' \
  -d '{"biz":"demo","intent":"echo","context":{"hello":"world"}}'
# 应返回 {"taskId":"..."}
curl -s http://localhost:8898/api/ai/tasks/<taskId>/draft
# 轮询后应返回 {"hello":"world"}
```

---

## Phase 2：MCP 协议接入（约 1 周）

> 目标：业务能力经 MCP 暴露给平台，AI 平台通过 MCP 自动发现并调用业务工具。

### T2.1 平台侧 MCP Client

**涉及文件：**
- 修改 `oAT-service/ai-platform/pom.xml`（增加 MCP client 依赖）
- 新增 `com.oAT.aiplatform.mcp.McpClientConfig`、`com.oAT.aiplatform.mcp.BusinessToolRegistry`

**实现要点：**
1. 依赖首选 `dev.langchain4j:langchain4j-mcp`（版本与 langchain4j 一致）；**若构建/运行报兼容错误，立即执行降级方案 B（见附录 A），不要卡住**。
2. `McpClientConfig`：从配置读取业务 MCP server 地址（`ai.mcp.business-url`，默认 `http://localhost:8899/mcp`）。
3. `BusinessToolRegistry`：启动时连接业务 MCP server，拉取工具列表；把工具注册进 LangChain4j `ToolSpecification` 列表，供 `AgentRunner` 调用。
4. `AgentRunner` 的 handler 增加 `mcpCall(intent, context)`：由 Agent 决定调用哪个工具。

**验收：** 业务 MCP server 未启动时平台不崩溃（工具列表为空，Agent 返回提示）；启动后能列出工具。

### T2.2 业务侧第一个 MCP Server（需求）

**涉及文件：**
- 修改 `oAT-service/oAT-service-web/pom.xml`（增加 MCP server 依赖）
- 新增 `com.oAT.web.mcp.McpServerConfig`
- 新增 `com.oAT.web.mcp.RequirementMcpServer`

**实现要点：**
1. **先通读 `VerificationAiOrchestrator.java`**，定位「阶段 1 需求结构化」相关方法（AC 提取、功能点拆解、质量评分）。
2. `RequirementMcpServer` 暴露两个工具：
   - `findRequirement(keyword)`：复用现有 `RequirementService` 检索需求文档（纯业务逻辑，不含 LLM）
   - `parseRequirement(doc)`：把原「阶段 1 需求结构化」逻辑迁入（**LLM 调用暂时保留在业务侧**，先保证链路通，T2.4 再上移）
3. 工具描述写清楚用途，供 LLM 自动选择。

**验收：** 业务启动后 `curl http://localhost:8899/mcp` 能发现两个工具。

### T2.3 ai-client 薄客户端

**涉及文件：**
- 新增 `com.oAT.web.ai.AiClient`（业务侧调用平台的封装）
- 新增 `com.oAT.web.ai.AiClientProperties`（`ai-platform.url` 配置，默认 `http://localhost:8898`）

**实现要点：**
```java
public class AiClient {
    public String submit(String biz, String intent, Map<String,Object> context); // POST /api/ai/tasks
    public AiDraft getDraft(String taskId);                                       // GET /api/ai/tasks/{id}/draft
    public void confirm(String taskId, boolean confirmed);                        // POST /api/ai/tasks/{id}/confirm
}
```

**验收：** 业务侧单元/集成测试：submit → getDraft → confirm 全链路通过（用 T1.7 的 echo 验证）。

### T2.4 LLM 调用上移（需求链路切平台）

**涉及文件：**
- 修改 `RequirementMcpServer.parseRequirement`（去掉业务侧 LLM 调用，改为「业务只返回结构化输入，LLM 由平台 Agent 完成」）
- 修改 `com.OAT.aiplatform.agent.AgentRunner`（`requirement.parse` 意图：Agent 先调 `findRequirement` 取数据，再调用模型做结构化解析，产出 JSON 草稿）
- 原业务侧 LLM 调用方法标记 `@Deprecated`（保留作 fallback，**暂不删除**）

**实现要点：** 平台 `AgentRunner` 为 `requirement.parse` 编写专用 handler：MCP 取数据 → LLM 解析 → payload 落草稿库。

**验收：** 提交 `requirement.parse` 任务，草稿返回结构化需求解析结果（AC 列表、功能点、评分）。

### T2.5 验收（Phase 2 完成判定）

```bash
# 1. 启动业务（8899）+ ai-platform（8898）
# 2. 平台能发现业务工具
curl -s http://localhost:8898/api/ai/tasks -X POST \
  -H 'Content-Type: application/json' \
  -d '{"biz":"oAT","intent":"requirement.parse","context":{"doc":"..."}}'
# 3. 轮询 draft，返回结构化需求草稿
```

---

## Phase 3：双轨 UI + 安全加固（约 1.5 周）

> 目标：浏览器里走通「手动录入 → 点 AI 辅助 → 草稿预览 → 人确认 → 落业务库」；密钥无明文。

### T3.1 API Key 移出 yaml

**涉及文件：**
- 修改 `oAT-service/oAT-ai/src/main/java/com/oAT/ai/config/AIConfig.java`
- 修改相关 `application*.yml`（业务模块中所有含 apiKey 的配置）
- 修改 `scripts/start-backend.sh`（启动时注入 `OAT_AI_API_KEY`）

**实现要点：**
1. 读 `AIConfig.java`，把 `@ConfigurationProperties` 绑定的 `apiKey` 改为 `@Value("${OAT_AI_API_KEY:}")` 或保留属性绑定但配置值改为 `${OAT_AI_API_KEY:}`。
2. 删除 yaml 中明文 key（用 `grep -rn "sk-\|api-key" oAT-service/ --include=*.yml --include=*.yaml` 排查全部位置）。
3. 启动脚本增加 `export OAT_AI_API_KEY="${OAT_AI_API_KEY:-}"`，缺省则启动时打印警告。

**验收：** 全仓库 `grep -rn "sk-[a-zA-Z0-9]\{20,\}" oAT-service/` 无命中；业务照常启动（设置环境变量后）。

### T3.2 草稿确认接口 + AI 生成标注字段

**涉及文件：**
- 新增 `oAT-service/oAT-service-web/src/main/resources/db/migration/V14__ai_generated_marker.sql`
- 新增 `com.oAT.web.ai.AiDraftConfirmController`

**实现要点：**
1. `V14`：给需求/用例相关主表增加 `ai_generated BOOLEAN DEFAULT FALSE` 标注字段（先确认表名：查 `V5__normalized_core.sql` / `V6__ai_verification.sql` 中需求、用例表名）。
2. `AiDraftConfirmController`：`POST /api/ai-proxy/drafts/{taskId}/confirm` → 调 `AiClient.confirm`；业务侧把草稿 payload 落业务库（复用现有 Service 的新增方法），并把 `ai_generated` 置 TRUE。
3. **AI 产出落库必须经过此接口，业务 Service 不直接接受平台写库**。

**验收：** 确认后业务库出现数据且 `ai_generated=true`。

### T3.3 前端 ai.ts + dev proxy

**涉及文件：**
- 新增 `oAT-web-frontend/src/api/ai.ts`
- 修改 `oAT-web-frontend/vite.config.ts`

**实现要点：**
1. `ai.ts` 封装：`submitAiTask(biz, intent, context)`、`getAiDraft(taskId)`、`confirmAiDraft(taskId, ok)`（复用 `http.ts` 的 fetch 封装）。
2. `vite.config.ts` 增加 dev proxy：`/ai-platform` → `http://localhost:8898`（生产由 nginx 转发，README 注明）。
3. 与后端约定：前端调业务 `/api/ai-proxy/*`（经业务代理），或直接调 `/ai-platform/*`（dev 直连）——**以最小改动为准，推荐 dev 直连平台**。

**验收：** 前端 `npm run dev` 后，`curl http://localhost:5176/ai-platform/api/ai/tasks` 能通到平台。

### T3.4 AI 辅助按钮 + 草稿确认组件

**涉及文件：**
- 新增 `oAT-web-frontend/src/components/ai/AiAssistButton.vue`
- 新增 `oAT-web-frontend/src/components/ai/AiDraftDialog.vue`
- 修改 `oAT-web-frontend/src/pages/VerificationWorkspacePage.vue`（需求/用例区域接入）

**实现要点：**
1. `AiAssistButton.vue`：props `intent`、`buildContext`；点击 → `submitAiTask` → 轮询 `getAiDraft`（沿用项目现有轮询模式，间隔 2s）。
2. `AiDraftDialog.vue`：展示草稿 JSON（可编辑文本框）→ 「确认落库 / 拒绝」按钮 → `confirmAiDraft`；草稿区顶部显示「AI 生成」徽标。
3. 在需求录入区和用例生成区各放一个 `AiAssistButton` + `AiDraftDialog`。

**验收：** 浏览器手动走通全流程；草稿带「AI 生成」标注。

### T3.5 验收（Phase 3 完成判定）

```bash
# 1. 全仓库无明文 apiKey
grep -rn "sk-" oAT-service/ --include=*.yml --include=*.yaml --include=*.java || echo OK
# 2. 前端类型检查通过
cd oAT-web-frontend && npx vue-tsc --noEmit
# 3. 浏览器：需求页 录入→AI 辅助→草稿→确认→落库（ai_generated=true）
```

---

## Phase 4：扩展 + 清理 + 回归（约 1 周）

> 目标：其余 4 域接入；删除旧耦合；全量回归通过。

### T4.1 其余 4 域 MCP Tool

**涉及文件：** 新增 `com.oAT.web.mcp.DefectMcpServer`、`CoverageMcpServer`、`GitMcpServer`、`VersionMcpServer`

**实现要点：**
1. 各 Server 从 `VerificationAiOrchestrator` 阶段 3/5 及 `GitImpactAnalysisService` 中拆出对应逻辑（**LLM 部分同步上移平台，业务侧只留数据能力**）。
2. 工具清单：
   - 缺陷：`createDefectDraft(finding)`（反向验证、风险定级草稿）
   - 覆盖率：`fetchCoverageFromCi(ciUrl, token)` + `analyzeCoverageGap(coverageData)`（**格式解析仍走现有 9 个确定性解析器，AI 只做语义缺口分析**）
   - Git：`analyzeGitImpact(commits)`（变更语义解读）
   - 版本：`analyzeVersionImpact(diff)`（版本影响分析草稿）
3. 平台侧 `AgentRunner` 为每个 intent 注册 handler。

**验收：** 每个新工具能被平台发现并产出草稿。

### T4.2 覆盖率两层确认

**涉及文件：** 只读确认，不改确定性解析器

**实现要点：** 确认 `fetchCoverageFromCi` 拉取后仍走现有解析器产出结构化 `CoverageData`；平台 Agent 仅消费结构化数据做语义分析。**若发现解析器被改，立即回退。**

**验收：** `git diff --stat` 中无覆盖率解析器文件变更。

### T4.3 删除旧耦合代码

**涉及文件：**
- `oAT-service/oAT-service-web/src/main/java/com/oAT/web/verification/VerificationAiOrchestrator.java`
- `oAT-service/oAT-service-web/src/main/java/com/oAT/web/verification/` 下相关类
- `GitImpactAnalysisService` 中已迁移的 LLM 方法

**实现要点：**
1. 每批删除前 `grep -rn "VerificationAiOrchestrator" oAT-service/oAT-service-web/src --include=*.java` 确认无引用。
2. 每批 2~3 个文件，删除后 `mvn -f oAT-service/pom.xml -pl oAT-service-web -am compile` + 跑测试。
3. 全删完后把调用点切到 `AiClient`/新链路（Feature Flag 已灰度稳定的前提下）。

**验收：** `VerificationAiOrchestrator` 及其耦合类完全移除，编译通过，测试通过。

### T4.4 全量回归

```bash
mvn -f oAT-service/pom.xml clean test
cd oAT-web-frontend && npx vue-tsc --noEmit && npx vite build
```

**验收：** 构建、测试、前端构建全部通过。

### T4.5 最终验收（8 条，全满足即完成）

- [ ] ① 点「AI 辅助」→ 草稿预览 → 人确认 → 落库，全程 ≤ 1 分钟有反馈
- [ ] ② 停止 ai-platform 进程，业务全部手动操作不受影响
- [ ] ③ DeepSeek 失败 → 自动重试/降级 fallback，页面不白屏、不报错
- [ ] ④ `apiKey` 不在任何 yaml/代码/日志明文出现
- [ ] ⑤ 第二个业务项目 3 天内按「暴露 MCP + 引 ai-client + 加双轨 UI」三步跑通（如暂无第二业务，则以「ai-platform 无任何 oAT 业务代码引用」代替验证）
- [ ] ⑥ 所有 AI 产出带「AI 生成」标注且可追溯
- [ ] ⑦ 覆盖率手动上传与 CI 拉取进入同一分析入口（格式解析仍走确定性 9 解析器）
- [ ] ⑧ 代码量净减：删除约 1500 行 AI 耦合代码，外部运行时依赖 4→0

### T4.6 更新 README

**涉及文件：** `README.md`（根目录）

**实现要点：** 新增「架构」小节（ai-platform / MCP / 双轨说明）与启动命令（含 ai-platform 启动、`OAT_AI_API_KEY` 环境变量说明）。

---

## 附录 A：风险与降级预案

| 风险 | 预案 |
|---|---|
| `langchain4j-mcp` 版本与现有 langchain4j 不兼容 | **降级方案 B**：平台内置 `HttpToolRegistry`——业务把工具暴露为普通 REST 端点（`/api/mcp-tools/{tool}`），平台侧用声明式 `HttpToolAdapter`（方法签名 + HTTP 调用）注册进 Agent；**架构不变，仅协议层从 MCP 降为 HTTP 适配**。T2.1 若 MCP 兼容失败即切换，不阻塞后续 |
| DeepSeek 限流/中断 | ModelClient 重试 2 次 + GracefulFallback 返回结构化错误，业务手动路径不受影响 |
| 拆 VerificationAiOrchestrator 出错 | 分 Task 迁移，原方法标 `@Deprecated` 保留 fallback；每批删除后编译 + 测试 |
| AI 草稿质量差 | 草稿不入库 + 人确认拦截 + 标注「AI 生成」便于追溯 |
| 前端轮询反馈慢 | 沿用现有轮询；WebSocket 实时推送列为后续可选增强 |

## 附录 B：明确推迟项（出现真实需求再加，每项独立）

多租户隔离/配额 · Vault 密钥管理 · Redis Stream 任务队列 · 多模型故障转移链 · Prometheus + 告警 · WebSocket 实时进度 · AgentScope Harness 多 Agent/沙箱

## 附录 C：迁移文件命名规则

- 业务 schema（oAT-service-web）：`V14__<描述>.sql`、`V15__...`，只增不改。
- ai_platform schema（ai-platform）：`V1__ai_draft.sql`、`V2__...`，从 V1 起，只增不改。
