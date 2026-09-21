# oAT 覆盖率功能资源目录（随包分发，extraResources）

本目录在打包时由 `electron-builder` 的 `extraResources` 复制到应用 `Resources/resources/`，
运行时由 `process.resourcesPath/resources/` 读取。

## 已放入的文件（覆盖率功能必需）

| 文件 | 来源 | 说明 |
|---|---|---|
| `xiaoxiao-jacoco-cli.jar` | xiaoxiao-jacoco 工程 `xiaoxiao-jacoco-cli/target` | 覆盖率 CLI（dump/report/merge/keys/stats/dumpclasses/setkey…），Java/Kotlin 后端调用 |
| `xiaoxiao-jacoco-agent.jar` | xiaoxiao-jacoco 工程 `xiaoxiao-jacoco-agent/target` | 探针，被测服务以 `-javaagent` 挂载 |

> 上述两个 jar 已拷贝到本目录（见文件时间戳）。重新构建 xiaoxiao-jacoco 后需同步更新。

## 可选：随包 JRE（已拍板：JRE 随 oAT 包分发）

将 JRE 解压到本目录的 `jre/`（结构：`jre/bin/java`）。
`electron/coverage/cliRunner.ts` 在运行时优先使用 `resources/jre/bin/java`，
找不到则回退系统 `java`。未提供 JRE 时，开发环境直接用系统 `java` 即可。

## 多语言后端工具链（无需随包，运行环境自带；各语言均为独立插件）

- Java/Kotlin：`xiaoxiao-jacoco-cli.jar`（上方）
- JavaScript / TypeScript：nyc（项目依赖或全局 `npx nyc`）
- Python：coverage.py（`pip install coverage`）
- Go：`go tool covdata` / `go tool cover`（Go 工具链）
- C / C++：lcov / genhtml（C/C++ 默认进程级归因）

每个语言是一个独立插件（`electron/coverage/lang/*.ts`），在「覆盖率分析」页以
**插件卡 + 参数表单 + 真实命令预览** 的形式呈现，命令与参数均由 UI 编辑。

## classfiles（覆盖率分母）= 仅本地路径

classfiles 只支持本地路径（被插桩类的字节码目录，如 `target/classes` 或 `dumpclasses` 拉回的目录）。
绝不从运行容器 classdumpdir 拿；如需从构建侧/镜像仓库获取，请先落到本地路径再填写。
