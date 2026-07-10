# oAT-service-web

`oAT-service-web` 是 oAccurateTest 的后端主服务。它负责管理项目和应用、处理版本和用例数据，并加载 `oAT-ai` 提供智能分析能力。

## 模块结构

```text
oAT-service-web/src/main/java/com/oAT/web/
├── control/          # Controller，包含 /api/* 前端接口
├── service/          # 业务服务接口与实现
├── esDao/            # 兼容保留的数据访问包
├── config/           # Spring 配置、异步线程池
├── security/         # 登录拦截与安全相关代码
├── domain/           # 图谱和视图领域模型
├── common/           # 通用工具
├── dto/              # DTO
└── exceptions/       # 业务异常

src/main/resources/
├── application.properties
├── db/mysql/          # MySQL 初始化脚本
```

## 构建

先构建 `oAT-ai`，再构建本模块：

```bash
cd ../oAT-service/oAT-ai
mvn clean install

cd ../oAT-service-web
mvn clean package
```

产物：

```text
target/oAT-service-web-1.0.0-SNAPSHOT.war
```

## 依赖服务

| 服务 | 用途 |
|---|---|
| MySQL | 项目、应用、版本、用例、成员、配置等结构化数据 |
| Git | 版本源码、Commit、Diff 和静态源码分析 |

## 数据库初始化

首次部署时，按文件名顺序执行 `src/main/resources/db/mysql/` 下的 SQL：

```text
phase2_api_endpoint.sql
phase2_version_center.sql
phase3_case_center.sql
phase4_static_source_info.sql
phase5_normalized_core.sql
```

## 关键配置

配置文件：

```text
src/main/resources/application.properties
```

### 服务端口

```properties
server.port=8899
```

如通过 `oAT-relay` 暴露服务，保持本服务端口不变，在 relay 中配置：

```properties
oat.relay.target-base-url=http://127.0.0.1:8899
```

### MySQL

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/oaccurate_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=123456
```

### 本地数据目录

```properties
oat.data.path=${user.home}/oAT/codeData/
```

该目录用于 Git 源码缓存、大载荷、静态源码压缩包等，服务进程需要读写权限。

### 上传大小

```properties
spring.servlet.multipart.max-file-size=2048MB
spring.servlet.multipart.max-request-size=2048MB
```

如果前面有 Nginx、网关或外部 Tomcat，也需要同步调整对应请求体限制。

### 用例链接模板

```properties
oat.usecase.defect-link-template=https://jira.example.com/browse/{id}
oat.usecase.prd-link-template=https://prd.example.com/doc/{id}
```

### AI 配置

`ai.*` 配置写在本模块的 `application.properties`，具体说明见 [oAT-ai README](../oAT-ai/README.md)。

## 启动

```bash
java -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

后台启动示例：

```bash
nohup java -jar target/oAT-service-web-1.0.0-SNAPSHOT.war > oat.log 2>&1 &
```

外部 Tomcat 部署时需使用 Tomcat 10+，以匹配 Spring Boot 3.x 的 Servlet 版本要求。

## 主要服务

| Service | 职责 |
|---|---|
| `VersionService` | 版本、分支、Commit 和 Diff |
| `UsecaseService` | 用例目录、详情和关联 |
| `ApiEndpointAnalysisService` | API 端点识别 |
| `AIInteractiveService` | AI 对话、上下文路由和流式输出 |

## 注意事项

- MySQL 应先于本服务启动。
- 首次部署执行全部 SQL；升级时只执行新增 phase。
- `oat.data.path` 会自动创建，但磁盘空间和权限需要提前确认。
- `oAT-relay` 只是转发层，不替代本服务的数据库或 AI 配置。
