# oAT-service

`oAT-service` 是服务端 Maven 聚合模块，负责统一管理 `oAT-ai` 和 `oAT-service-web` 的构建。它本身不启动进程，入口在 `oAT-service-web`。

## 模块结构

```text
oAT-service/
├── pom.xml
├── oAT-ai/           # LLM 接入模块，jar
└── oAT-service-web/  # 后端主服务，war
```

## 构建方式

在聚合模块下构建全部服务端模块：

```bash
cd oAT-service
./oAT-service-web/mvnw -f pom.xml clean install
```

只构建后端主服务时，先安装 `oAT-ai` 再打包 `oAT-service-web`：

```bash
cd oAT-service/oAT-ai
../oAT-service-web/mvnw clean install

cd ../oAT-service-web
./mvnw -f ../pom.xml package -DskipTests
```

## 依赖关系

| 模块 | 产物 | 作用 |
| --- | --- | --- |
| `oAT-ai` | `jar` | 封装 LLM 配置、Provider 适配和 `LLMService` |
| `oAT-service-web` | `war` | 提供项目、版本、用例、验证基线、图谱和 AI 验证相关 API |

## 运行入口

`oAT-service` 不直接运行。启动后端主服务：

```bash
cd oAT-service/oAT-service-web
./start.sh
```

如果要手动执行：

```bash
java --enable-native-access=ALL-UNNAMED -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

## 相关文档

- [oAT-service-web](oAT-service-web/README.md)
- [oAT-ai](oAT-ai/README.md)
