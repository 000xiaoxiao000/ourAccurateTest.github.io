# oAT 多人协作方案

> **本方案替代原「共享同步方案」。** 抛开共享盘联邦的前提，从协作需求倒推架构。
> 状态：方案设计稿，未实现。不改动任何业务代码。

---

## 1. 从需求倒推架构

四项协作需求对数据一致性的要求**并不相同**，这决定了架构：

| 协作需求 | 数据特性 | 需要什么 |
|---|---|---|
| exec 合并出团队总报告 | exec 是 append-only 文件，天然不冲突 | 有个汇聚点即可 |
| **用例分工与认领** | **独占性状态** | **中心仲裁**——两人同时认领必须只有一个成功 |
| **统一 buildId 硬约束** | **全局唯一声明** | **中心权威**——"当前基线是哪个版本"只能有一个答案 |
| 进度看板 | 聚合计算 | 中心聚合最省事 |

**结论：必须有服务端。**

不是因为同步做不到，而是四项需求里有两项（认领、基线）是**独占性 / 全局唯一**的，异步联邦无法仲裁。共享盘方案只能解决"互相看得到"，解决不了"这件事归谁"。

---

## 2. 核心设计：混合一致性模型

不需要把所有东西都做成实时，只把**必须仲裁的**做成实时：

| 类别 | 内容 | 一致性 | 通道 |
|---|---|---|---|
| **强一致** | 用例认领 / 释放、基线版本设置、项目与成员管理 | 立即生效，失败立刻知道 | 直接调服务端 API |
| **最终一致** | 采集的请求明细、exec 上传 | 允许延迟，离线也能继续采 | 本地 outbox + 联网同步 |

好处：**服务端挂了不影响采集**——本地库照常记录，联网后补传。认领/基线这类操作失败会立即提示，不会静默丢。

---

## 3. 架构

```
┌─ 客户端 A / B / C ────────────────────────┐
│  本地库（SQLite）  离线可采、可查            │
│  outbox           离线操作排队，联网补传     │
│  同步器            增量 push / pull         │
└──────────────┬────────────────────────────┘
               │ HTTP（内网）
┌─ 服务端 ─────▼────────────────────────────┐
│  API    项目 / 成员 / 任务认领 / 基线 / 进度  │
│  DB     Postgres 或 SQLite（起步）          │
│  存储    exec / classfiles / 报告 / 基线    │
└───────────────────────────────────────────┘
```

客户端保留本地库的意义：离线可采集、历史数据可查、服务端不可用时仍能干活。

---

## 4. 数据模型（服务端）

```sql
-- 项目：含当前基线（全局唯一声明）
CREATE TABLE projects (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  current_build_id TEXT NOT NULL,     -- 硬约束的锚点，例：order-service@a1b2c3d
  created_by       TEXT NOT NULL,
  created_at       INTEGER NOT NULL
);

CREATE TABLE users (
  id   TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE members (
  project_id TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  role       TEXT NOT NULL,           -- owner | admin | developer | viewer
  PRIMARY KEY (project_id, user_id)
);

-- 用例任务：分工与认领的核心表
CREATE TABLE case_tasks (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  name       TEXT NOT NULL,           -- 例：用户登录-正常流程
  module     TEXT,                    -- 例：订单 / 支付
  owner_id   TEXT,                    -- 认领人；NULL = 未认领
  status     TEXT NOT NULL,           -- todo | doing | done
  claimed_at INTEGER,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  version    INTEGER NOT NULL DEFAULT 1,   -- 乐观锁：认领靠它防并发
  UNIQUE (project_id, name)
);

-- 各成员上传的 exec
CREATE TABLE coverage_runs (
  id          TEXT PRIMARY KEY,
  project_id  TEXT NOT NULL,
  user_id     TEXT NOT NULL,
  build_id    TEXT NOT NULL,          -- 必须与项目 current_build_id 一致
  cov_key     TEXT NOT NULL,          -- <projectId>/<memberId>
  exec_path   TEXT NOT NULL,
  size        INTEGER,
  sha256      TEXT,
  uploaded_at INTEGER NOT NULL
);

-- 团队总报告（merge 产出）
CREATE TABLE team_reports (
  id             TEXT PRIMARY KEY,
  project_id     TEXT NOT NULL,
  build_id       TEXT NOT NULL,
  merged_exec    TEXT NOT NULL,
  report_path    TEXT NOT NULL,
  coverage_pct   REAL,
  covered_lines  INTEGER,
  total_lines    INTEGER,
  created_at     INTEGER NOT NULL
);

-- 请求摘要（量大，只存摘要，body 不上传）
CREATE TABLE requests (
  id         TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  session_id TEXT,
  cov_key    TEXT,
  method     TEXT,
  url        TEXT,
  status_code TEXT,
  duration   INTEGER,
  timestamp  INTEGER
);
```

### 认领的并发控制（关键）

```sql
UPDATE case_tasks
   SET owner_id = :me, status = 'doing', claimed_at = :now,
       version = version + 1, updated_at = :now, updated_by = :me
 WHERE id = :taskId AND version = :expectedVersion
```

用**乐观锁**：`version` 不匹配则更新行数为 0，返回「已被他人认领」，客户端立即刷新。这是共享盘方案做不到的。

---

## 5. 协作闭环

一次完整的团队协作流程：

1. **建项目**：「订单服务-v2.3」，设基线 `build_id = order-service@a1b2c3d`（classfiles 由 CI 产出）
2. **建任务**：导入或手动创建用例任务列表（登录、下单、支付、退款…），初始 `status = todo`
3. **认领**：张三认领「登录」「下单」→ 服务端写 `owner_id`，其他人列表里立刻显示已被认领
4. **采集**：张三按 `key = order-service/zhangsan` 采集 → exec 上传（校验 buildId 匹配）
5. **并行**：李四认领「支付」→ 采集 → 上传，互不干扰
6. **合并**：触发 merge → 所有人的 exec 合并 → 生成团队总覆盖率报告；同时 `--perkey` 出各人贡献
7. **看板**：覆盖率 X%、已覆盖 Y/N 个用例、各人贡献占比、未认领任务数

---

## 6. key 规范与 buildId 约束

### 6.1 key 规范（决定能不能拆出各人贡献）

```
cov_key = <projectId>/<memberId>      例：order-service/zhangsan
```

**为什么必须**：JaCoCo 覆盖率是**布尔位图的并集**，多人 exec 合并后能算出「团队总共覆盖了多少」，但**算不出「张三贡献了多少」**——除非每人采集时用各自的 key 隔离。

所以：

- 各人采集：`cov_key = 项目/成员`（客户端自动带上，不让手填）
- dump 时按 key 隔离，得到各自的 exec
- 最终：**一次 merge 出团队总账 + 一次 `--perkey` 出各人贡献**

### 6.2 buildId 硬约束

项目表里的 `current_build_id` 是唯一的锚点。三处强制校验：

| 时机 | 校验 | 不匹配时 |
|---|---|---|
| 上传 exec | exec 对应的 buildId == `current_build_id` | **拒绝上传**，提示当前基线版本 |
| 触发 merge | 参与合并的 exec buildId 全部一致 | **拒绝合并**，列出不一致的成员 |
| 生成报告 | classfiles 与 exec 的 buildId 一致 | **拒绝生成**（扩展现有 `warnIfNoExecData` 思路） |

不一致时合并出来的报告没有意义——分母（classfiles）和分子（exec）对不上版本。

---

## 7. 产物与 merge

### 7.1 存储布局

```
<serverStorage>/
  projects/<projectId>/
    builds/<buildId>/
      classfiles.zip          CI 产出（优先）或成员上传
    runs/<userId>/<ts>.exec   各人上传的 exec
    merged/<buildId>.exec     merge 产出
    reports/<buildId>/<ts>/   团队总报告 HTML
    perkey/<buildId>/         perkey 各人贡献报告
```

### 7.2 合并命令

复用现有 `jacoco.ts` 的 merge 命令（`destfile: jacoco-merged.exec`），一次调用合并所有成员的 exec：

```
java -jar cli.jar merge --destfile <merged.exec> --execdir <runs/> --buildid <buildId>
```

`--execdir` 会自动收集目录下所有 exec；但仍需在**调用前**按 buildId 过滤，避免混入其他版本的 exec。

### 7.3 各人贡献

```
java -jar cli.jar report <merged.exec> --classfiles ... --perkey --html <perkey/>
```

`--perkey` 一条命令出所有 key 的报告（memory 里记过：不要逐 key 调用）。

---

## 8. API 清单

**强一致（立即生效）**

```
POST   /projects                      建项目（含 current_build_id）
GET    /projects                      项目列表
POST   /projects/:id/build            设置/切换基线版本
GET    /projects/:id/tasks            用例任务列表（含 owner / status）
POST   /tasks                         批量创建任务
POST   /tasks/:id/claim               认领（乐观锁，version 不匹配返回冲突）
POST   /tasks/:id/release             释放
PATCH  /tasks/:id/status              状态流转
GET    /projects/:id/members          成员列表
```

**最终一致（可离线）**

```
POST   /runs                          上传 exec（校验 buildId）
GET    /runs?project=&build=          列出各人 exec
POST   /requests/batch                批量上传请求摘要
GET    /requests?project=             拉取请求摘要
```

**计算与产出**

```
POST   /reports/merge                 触发 merge + 生成团队总报告
GET    /reports?project=              报告列表
GET    /projects/:id/dashboard        进度看板数据
```

---

## 9. 客户端改造

| 位置 | 改动 |
|---|---|
| `electron/identity.ts`（新） | 用户登录态、token、当前项目 |
| `electron/database.ts` | 加 `project_id` / `owner_id` / `synced`；保留本地查询（离线可用） |
| `electron/sync/`（新） | outbox、增量 push / pull、冲突留痕。**按项目惯例不 import electron，可纯 Node 单测** |
| `electron/coverage/index.ts` | exec 上传、merge 触发、perkey 报告拉取；路径参数走「资源解析」（buildId → 本地缓存路径） |
| `electron/main.ts` | 上述 API 的 IPC 封装 + 同步进度事件（照抄 `coverage-probe-heartbeat` 推送模式） |
| UI | 项目切换器、任务看板（认领/状态）、成员列表、上传进度、团队报告入口、进度看板页 |

⚠️ 每新增一个 IPC 必须同步 4 处：`main.ts` handler + `preload.ts` bridge + `electron.d.ts` 声明 + Vue 调用。

---

## 10. 实施步骤

| 阶段 | 内容 | 结果 |
|---|---|---|
| **P0 服务端骨架** | 项目 / 成员 / 基线、登录、存储目录 | 有权威的基线声明与成员管理 |
| **P1 任务与认领** | `case_tasks`、认领 API（乐观锁）、任务看板 UI | 分工不撞车 |
| **P2 exec 与合并** | exec 上传 + buildId 校验、merge、团队总报告、perkey 各人贡献 | **协作的核心产出** |
| **P3 同步与看板** | 请求摘要同步、离线 outbox、进度看板 | 完整可用 |

建议 P0+P1 一起做——没有任务认领，服务端就只是个文件服务器。

---

## 11. 待定

1. **服务端技术栈**：Node + SQLite 起步（够用、零运维），还是直接 Postgres（多人并发写入更稳）？
2. **用户认证**：独立账号密码，还是对接内网 LDAP / 企业微信？
3. **请求明细上传范围**：只传摘要（推荐，量小 + 降外泄面），还是含 body？
4. **classfiles 来源**：CI 产出为主，成员上传作为兜底（需显示来源与上传者）。
5. **merge 触发时机**：手动点按钮，还是检测到新 exec 自动合并？
