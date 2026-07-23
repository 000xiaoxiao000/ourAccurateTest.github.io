# oAT-ai

`oAT-ai` 是后端的 LLM 接入模块，作为 `jar` 被 `oAT-service-web` 依赖。它不提供独立 HTTP 服务，主要负责绑定 `ai.llm.*` 配置、创建模型客户端，并向业务层暴露 `LLMService`。

## 目录结构

```text
oAT-ai/src/main/java/com/oAT/ai/
├── config/
│   ├── AIAutoConfiguration.java  # Spring 自动装配入口
│   ├── AIConfig.java             # LLM Provider 配置和 Bean 装配
│   └── AIConfigProperties.java   # ai.llm.* 配置绑定
└── service/
    ├── LLMService.java           # 文本生成调用接口
    └── impl/
        └── LLMServiceImpl.java   # LLMService 默认实现
```

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 17 | 编译和运行基础 |
| Spring Boot Autoconfigure | 配置属性绑定和自动装配 |
| LangChain4j 1.12.2 | LLM 客户端抽象 |
| OpenAI 兼容接口 | OpenAI、DeepSeek、自定义模型服务 |
| Ollama | 本地模型调用 |

## 构建

从服务端聚合模块构建：

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml clean install
```

只安装本模块：

```bash
cd oAT-service/oAT-ai
../oAT-service-web/mvnw clean install
```

`oAT-service-web` 单独构建前，本模块需要先安装到本地 Maven 仓库。

## 配置

`oAT-ai` 不维护独立运行配置。实际配置写在：

```text
oAT-service/oAT-service-web/src/main/resources/application.properties
```

常用配置：

```properties
ai.llm.enabled=true
ai.llm.provider=deepseek
ai.llm.base-url=https://api.deepseek.com
ai.llm.api-key=${AI_LLM_API_KEY:your-api-key}
ai.llm.model=${AI_LLM_MODEL:deepseek-chat}
ai.llm.max-tokens=8192
ai.llm.temperature=0.7
ai.llm.timeout=300
ai.llm.log-requests=false
ai.llm.log-responses=false
```

支持的 `provider`：

| provider | 说明 |
| --- | --- |
| `openai` | OpenAI API |
| `deepseek` | DeepSeek API |
| `ollama` | 本地 Ollama 服务 |
| `custom` | 兼容 OpenAI 协议的自定义服务 |

## 使用方式

业务代码通过 Spring 注入 `LLMService`，由 `VerificationAiOrchestrator` 等验证流程调用模型完成结构化分析。关闭 `ai.llm.enabled` 后，模型相关 Bean 不会装配。
