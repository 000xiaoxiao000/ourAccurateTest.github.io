# oAT-ai

`oAT-ai` 是 oAccurateTest 的 LLM 接入模块。当前仅保留基础模型配置和 `LLMService`，供 `oAT-service-web` 的需求一致性验证流程调用。

## 模块结构

```text
oAT-ai/src/main/java/com/oAT/ai/
├── config/          # Spring Boot 自动装配与 ai.llm.* 配置绑定
└── service/         # LLMService
```

## 构建

`oAT-ai` 会被 `oAT-service-web` 以 jar 方式依赖，需先于后端主服务构建：

```bash
cd oAT-service/oAT-ai
mvn clean install
```

## 配置

所有 `ai.llm.*` 配置都写在 `oAT-service-web/src/main/resources/application.properties` 中。

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
```

支持的 provider：

| provider | 说明 |
|---|---|
| `openai` | OpenAI API |
| `deepseek` | DeepSeek API |
| `ollama` | 本地 Ollama，无需 API Key |
| `custom` | 兼容 OpenAI 协议的自定义服务 |
