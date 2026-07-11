# oAT-ai

`oAT-ai` 是 oAccurateTest 的 AI 智能分析模块。它基于 LangChain4j 实现 LLM 接入、工具调用、对话记忆、语义缓存、反馈学习和运行时模型切换，并作为 `oAT-service-web` 的依赖模块运行。

## 模块结构

```text
oAT-ai/src/main/java/com/oAT/
├── ai/
│   ├── agent/
│   │   ├── tools/       # 覆盖率、缺陷、性能、链路、测试推荐等工具
│   │   ├── cache/       # 语义缓存
│   │   ├── parallel/    # 工具并行执行
│   │   ├── AIAgent.java
│   │   ├── AIAgentService.java
│   │   ├── AgentContext.java
│   │   ├── AgentDataProvider.java
│   │   └── ConversationMemoryService.java
│   ├── config/          # Spring Boot 自动装配与 ai.* 配置绑定
│   └── service/         # LLMService
└── agent/
    └── AISelfLearningService.java
```

## 构建

`oAT-ai` 会被 `oAT-service-web` 以 jar 方式依赖，需先于后端主服务构建：

```bash
cd oAT-service/oAT-ai
mvn clean install
```

## 运行方式

本模块不单独启动。`oAT-service-web` 启动时会加载 `oAT-ai` 的自动装配类，并通过 `AgentDataProviderImpl` 提供项目、应用、快照、链路、覆盖率等平台数据。

## 内置工具

| 工具 | 能力 |
|---|---|
| `CoverageTool` | 覆盖率报告查询与分析 |
| `CoverageWorkflowTool` | 覆盖率生成流程指引 |
| `BugDetectTool` | 缺陷检测与定位 |
| `DefectStatisticsTool` | 缺陷统计与趋势 |
| `PerformanceAnalysisTool` | 性能瓶颈识别 |
| `CallChainAnalysisTool` | 调用链分析 |
| `CallChainCompareTool` | 跨版本链路对比 |
| `CodeQualityTool` | 代码质量分析 |
| `CodeRelationTool` | 代码调用关系分析 |
| `SnapshotTool` | 系统快照查询 |
| `TraceQueryTool` | 链路节点查询 |
| `TestcaseRecommendationTool` | 测试用例推荐 |
| `AppStatusTool` | 应用在线状态查询 |
| `ProjectInfoTool` | 项目信息查询 |

## 配置

所有 `ai.*` 配置都写在 `oAT-service-web/src/main/resources/application.properties` 中。

### LLM 基础配置

```properties
ai.llm.enabled=true
ai.llm.provider=deepseek
ai.llm.base-url=https://api.deepseek.com
ai.llm.api-key=your-api-key
ai.llm.model=deepseek-chat
ai.llm.max-tokens=8192
ai.llm.temperature=0.7
ai.llm.timeout=300
ai.llm.log-requests=false
ai.llm.log-responses=false
ai.llm.system-prompt-prefix=你是一个专业的代码覆盖率分析助手...
```

支持的 provider：

| provider | 说明 |
|---|---|
| `openai` | OpenAI API |
| `deepseek` | DeepSeek API |
| `ollama` | 本地 Ollama，无需 API Key |
| `custom` | 兼容 OpenAI 协议的自定义服务 |

模型需支持 Function Calling / Tools，否则工具路由不可用。

### 增强功能

```properties
ai.enhanced.semantic-cache.enabled=true
ai.enhanced.semantic-cache.threshold=0.85
ai.enhanced.conversation.max-rounds=20
ai.enhanced.self-learning.enabled=true
ai.enhanced.self-learning.interval-hours=6
ai.enhanced.self-learning.knowledge-hit-enabled=true
ai.enhanced.self-learning.knowledge-hit-threshold=0.7
ai.enhanced.self-learning.dynamic-guide-enabled=true
ai.enhanced.feedback.retention-days=30
```

### 页面上下文路由

```properties
ai.interactive.route.coverage.keywords=覆盖率页,coverage,覆盖率详情,覆盖率报告
ai.interactive.route.trace.keywords=监控页,monitor,调用链页,链路页
ai.interactive.route.snapshot.keywords=快照页,snapshot,我的快照,快照列表
ai.interactive.route.app.keywords=应用页,应用中心,app/list,app/online
ai.interactive.route.code-relation.keywords=代码关系,类关系,callgraph,关系图
```

## 扩展工具

在 `oAT-service-web` 中创建 Spring Bean，并使用 LangChain4j 的 `@Tool` 注解声明工具方法：

```java
@Component
public class MyCustomTool {

    @Tool("描述这个工具的能力，让 AI 知道何时调用它")
    public String analyze(@P("参数说明") String input) {
        return "分析结果";
    }
}
```

再将 Bean 注入 `AIAgent` 并注册到工具列表。

## 注意事项

- AI 分析质量依赖平台数据完整性，快照、链路、覆盖率和静态源码需要先入库。
- 语义缓存依赖 Redis；Redis 不可用时会跳过缓存，不影响正常对话。
- 本地 Ollama 响应可能较慢，建议把 `ai.llm.timeout` 设置为 300 秒或更高。
