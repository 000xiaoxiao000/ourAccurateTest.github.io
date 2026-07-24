# oAT-service

`oAT-service` 是服务端 Maven 聚合模块，统一构建 `oAT-ai` 和 `oAT-service-web`。该目录本身不启动服务，运行入口在 `oAT-service-web`。

## 模块结构

```text
oAT-service/
├── pom.xml
├── oAT-ai/           # LLM 接入模块，打包为 jar
└── oAT-service-web/  # 后端主服务，打包为 war
```

## 子模块职责

| 模块 | 产物 | 职责 |
| --- | --- | --- |
| `oAT-ai` | `jar` | 绑定 `ai.llm.*` 配置，适配 OpenAI/DeepSeek/Ollama/自定义模型服务，提供 `LLMService` |
| `oAT-service-web` | `war` | 提供平台 API、数据库迁移、项目/版本/用例、验证图谱、质量门禁和 Git 影响分析能力 |

## 构建

仓库没有在 `oAT-service/` 根目录放置独立 `mvnw`，可复用 `oAT-service-web` 下的 Maven wrapper。

构建全部服务端模块：

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml clean install
```

运行服务端测试：

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml test
```

只构建后端主服务：

```bash
cd oAT-service/oAT-service-web
./mvnw -f ../pom.xml package -DskipTests
```

## 运行

```bash
cd oAT-service/oAT-service-web
./start.sh
```

`start.sh` 会运行：

```bash
java --enable-native-access=ALL-UNNAMED -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

## 相关文档

- [oAT-service-web](oAT-service-web/README.md)
- [oAT-ai](oAT-ai/README.md)
