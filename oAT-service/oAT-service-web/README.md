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
phase6_ai_verification.sql
```

`phase6_ai_verification.sql` 提供联邦式 AI 需求一致性验证所需的轻量数据表，仅保存外部资产快照、分析基线、AC 投影、追溯边、AI 发现、人工审核状态和质量门禁结果；需求、用例、Bug、执行计划等事实源仍保留在外部平台。

已有数据库升级时，如果曾执行过早期 `phase6_ai_verification.sql`，需按脚本顶部的 `Upgrade note` 为 `oat_verification_baseline` 补充执行证据和覆盖率证据列。

## 关键配置

配置文件：

```text
src/main/resources/application.properties
```

### 服务端口

```properties
server.port=8899
```

### PostgreSQL

```properties
spring.datasource.url=${OAT_DB_URL:jdbc:postgresql://127.0.0.1:5432/ai_requirement_verification}
spring.datasource.username=${OAT_DB_USERNAME:postgres}
spring.datasource.password=123456
spring.datasource.driver-class-name=org.postgresql.Driver
oat.datasource.postgresql.create-database-if-missing=true
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
./start.sh
```

后台启动示例：

```bash
nohup ./start.sh > oat.log 2>&1 &
```

如果必须手工执行 `java -jar`，需要带上 JDK native access 参数，避免新版 JDK 对 Tomcat Native/APR 的限制预警：

```bash
java --enable-native-access=ALL-UNNAMED -jar target/oAT-service-web-1.0.0-SNAPSHOT.war
```

外部 Tomcat 部署时需使用 Tomcat 10+，以匹配 Spring Boot 3.x 的 Servlet 版本要求。

## 主要服务

| Service | 职责 |
|---|---|
| `VersionService` | 版本、分支、Commit 和 Diff |
| `UsecaseService` | 用例目录、详情和关联 |
| `ApiEndpointAnalysisService` | API 端点识别 |
| `VerificationService` | AI 需求一致性验证、快照基线、追溯矩阵、证据审核和质量门禁 |

## AI 需求一致性验证接入

验证平台支持三类轻量接入：

- 文件或粘贴：需求、用例、源码、执行报告和覆盖率报告都保存为一次性分析快照。
- Git 源码快照：通过应用已有仓库配置或手填仓库地址，按分支/Commit 拉取源码并抽取 Java 文件摘要作为 SOURCE 证据。
- 外部回写链接：在没有 Jira、禅道、TAPD 等真实连接器时，使用 `link-only` 模式记录外部 Bug、任务或评论链接，并保存回写审计。

## 注意事项

- `oat.data.path` 会自动创建，但磁盘空间和权限需要提前确认。
