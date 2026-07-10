# AI 需求一致性验证平台

AI 需求一致性验证平台面向研发、测试和质量保障团队，用 AI 辅助比对需求文档、测试用例和源代码，识别需求遗漏、用例缺失、预期错误和代码实现偏差。平台结合静态源码分析、版本 Diff 与 AI 工具编排，建立“需求 -> 用例 -> 代码”的追溯关系。

> 使用声明：本项目仅供个人学习、技术研究与交流使用，不得用于商业用途或未经授权的生产环境部署。使用者需自行遵守相关法律法规和第三方组件许可协议。

## 交流与反馈

| 添加作者微信 | 微信交流入口 | AI + 精准测试实战交流群 |
|---|---|---|
| 扫码添加好友，备注 `oAT` / `精准测试`。 | 交流项目使用、部署问题与二次开发思路。 | 讨论 AI 测试分析和工程落地。 |
| <img src="docs/assets/wechat-friend.png" alt="添加作者微信二维码" width="220"> | <img src="docs/assets/wechat-contact.jpg" alt="微信交流二维码" width="220"> | <img src="docs/assets/ai-testing-group.png" alt="AI + 精准测试实战交流群二维码" width="220"> |

## 模块总览

```text
AIRequirementVerification/
├── oAT-service/
│   ├── oAT-ai/           # LangChain4j AI 分析模块
│   └── oAT-service-web/  # 平台后端主服务
├── oAT-relay/            # HTTP 转发中继
├── oAT-web-frontend/     # Web 前端
├── oAT-traffic-capture/  # Electron 桌面流量采集器
└── docs/                 # 文档与图片资源
```

## 数据流

```text
oAT-service-web
  ├─ MySQL：项目、应用、版本、用例、成员等结构化数据
  ├─ Git 仓库：源码、Commit、Diff
  └─ oAT-ai：AI 工具编排与 LLM 调用
       ▼
  oAT-web-frontend

可选入口：
  oAT-relay：在隔离网络中转发前端请求
  oAT-traffic-capture：采集桌面流量
```

## 核心能力

- 版本中心：管理应用版本、分支、Commit、Git Diff 和变更影响范围。
- 用例中心：管理用例目录、用例详情、缺陷/PRD 链接和导入导出。
- API 端点分析：识别 HTTP 接口并关联源码信息。
- AI 智能分析：基于 LangChain4j 和工具调用实现需求、用例、代码分析。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3.6、JDBC |
| AI | LangChain4j 1.12.2，支持 OpenAI / DeepSeek / Ollama / 兼容 OpenAI 协议接口 |
| 存储 | MySQL 5.7+/8.x |
| 前端 | Vue 3、TypeScript、Vite 7、Pinia、Vue Router |
| 桌面端 | Electron 30、Vue 3、Vite 5、SQLite、http-mitm-proxy |

## 环境要求

| 组件 | 建议版本 | 用途 |
|---|---|---|
| JDK | 17+ | Java 模块构建与运行 |
| Maven | 3.8+ | Java 模块构建 |
| Node.js | 18+ | 前端和桌面端构建 |
| MySQL | 5.7+ / 8.x | 结构化数据 |

## 构建顺序

模块间存在本地 Maven 依赖，建议按以下顺序构建：

```bash
# 1. 构建 AI 模块
cd oAT-service/oAT-ai
mvn clean install

# 2. 构建后端主服务
cd ../oAT-service-web
mvn clean package

# 3. 构建 HTTP 中继，可选
cd ../../oAT-relay
mvn clean package

# 4. 构建 Web 前端，可选
cd ../oAT-web-frontend
npm install
npm run build

# 5. 构建桌面流量采集器，可选
cd ../oAT-traffic-capture
npm install
npm run build
```

## 启动顺序

1. 启动 MySQL。
2. 初始化 MySQL 表结构，脚本位于 `oAT-service/oAT-service-web/src/main/resources/db/mysql/`。
3. 启动 `oAT-service-web`。
4. 按需启动 `oAT-relay`。
5. 启动 `oAT-web-frontend` 或部署前端静态资源。
6. 按需启动 `oAT-traffic-capture` 采集流量。

## 模块文档

- [oAT-service](oAT-service/README.md)
- [oAT-service-web](oAT-service/oAT-service-web/README.md)
- [oAT-ai](oAT-service/oAT-ai/README.md)
- [oAT-relay](oAT-relay/README.md)
- [oAT-web-frontend](oAT-web-frontend/README.md)
- [oAT-traffic-capture](oAT-traffic-capture/README.md)
