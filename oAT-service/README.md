# oAT-service

`oAT-service` 是 oAccurateTest 的服务端 Maven 聚合模块，统一管理 AI 分析模块和后端主服务。该模块本身不启动进程，主要用于聚合构建和维护服务端公共构建配置。

## 模块结构

```text
oAT-service/
├── pom.xml
├── oAT-ai/           # AI 分析能力，作为 jar 被 oAT-service-web 依赖
└── oAT-service-web/  # 后端主服务，负责 API、版本、用例等能力
```

## 构建

可以在聚合模块中一次性构建两个服务端子模块：

```bash
cd ../oAT-service
mvn clean install
```

也可以按依赖顺序分别构建：

```bash
cd oAT-ai
mvn clean install

cd ../oAT-service-web
mvn clean package
```

## 子模块职责

| 模块 | 打包 | 职责 |
|---|---|---|
| `oAT-ai` | jar | LangChain4j 集成、LLM 配置、AI 工具注册、语义缓存和对话记忆 |
| `oAT-service-web` | war | 平台 API、版本中心、用例中心和 AI 对话入口 |

## 运行

`oAT-service` 不直接运行。服务端进程由 `oAT-service-web` 启动：

```bash
cd oAT-service-web
java -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

详细数据库初始化、配置项和启动说明见：

- [oAT-service-web README](oAT-service-web/README.md)
- [oAT-ai README](oAT-ai/README.md)

## 注意事项

- 服务端统一使用 JDK 17+。
- `oAT-ai` 必须先安装到本地 Maven 仓库，`oAT-service-web` 才能单独构建成功。
- `oAT-relay` 是独立模块，不属于 `oAT-service` 聚合构建。
