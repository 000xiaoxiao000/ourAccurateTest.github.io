# oAT-relay

`oAT-relay` 是 oAccurateTest 的轻量级 HTTP 转发中继。它用于测试网络隔离、客户端无法直连平台、统一代理出口或边缘节点部署场景，接收 Agent、覆盖率 SDK、桌面采集器和前端请求，并按原始路径转发到 `oAT-service-web`。

## 模块结构

```text
oAT-relay/
├── src/main/java/com/oAT/relay/
│   ├── OAtRelayApplication.java  # Spring Boot 启动类
│   ├── RelayController.java      # 通用转发入口
│   └── RelayProperties.java      # oat.relay.* 配置绑定
└── src/main/resources/
    └── application.properties    # 默认端口和目标服务地址
```

## 构建

```bash
cd oAT-relay
mvn clean package
```

产物：

```text
target/oAT-relay-1.0.0-SNAPSHOT.jar
```

## 启动

先启动 `oAT-service-web`，再启动 relay：

```bash
java -jar target/oAT-relay-1.0.0-SNAPSHOT.jar
```

默认配置：

```properties
server.port=18089
oat.relay.target-base-url=http://127.0.0.1:8899
```

通过启动参数覆盖：

```bash
java -jar target/oAT-relay-1.0.0-SNAPSHOT.jar \
  --server.port=18089 \
  --oat.relay.target-base-url=http://oat-service-web:8899
```

## 使用方式

客户端把原本的 `oAT-service-web` 地址替换为 relay 地址，接口路径保持不变：

```text
http://127.0.0.1:8899/api/...
http://127.0.0.1:18089/api/...
```

Agent 配置：

```properties
server=127.0.0.1:18089
```

覆盖率 SDK、桌面采集器和前端开发代理也可以指向 relay，例如 `http://127.0.0.1:18089`。

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `18089` | relay 监听端口 |
| `oat.relay.target-base-url` | `http://127.0.0.1:8899` | 目标 `oAT-service-web` 地址 |
| `oat.relay.connect-timeout-ms` | `3000` | 转发连接超时 |
| `oat.relay.read-timeout-ms` | `10000` | 转发读取超时 |
| `oat.relay.max-body-size-mb` | `20` | 单次请求体大小上限 |
| `oat.relay.auth-token` | 空 | 非空时覆盖转发请求的 `Authorization` 头 |

## 注意事项

- relay 只做 HTTP 转发，不承担鉴权、存储、报告生成或 AI 分析职责。
- 大覆盖率文件上送时，需要同步调整 relay、`oAT-service-web`、Nginx/网关的请求体大小限制。
- 生产部署建议在网关层配置 TLS、来源 IP 限制、访问日志和限流策略。

