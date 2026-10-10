# 开发指南

## 编译依赖

需要 Java 21。使用仓库内 Gradle Wrapper；编译依赖配套 [Voxy Forge](https://github.com/KNaiFen/voxy) 的**开发版 JAR**，安装版 JAR 不能替代。

```sh
git clone https://github.com/KNaiFen/voxy.git
cd voxy
git checkout 2294097bca526f6a629f7955afdd6d4c45b3cef0
./gradlew :1.20.1-forge:jar
```

开发版输出在该仓库的 `versions/1.20.1-forge/build/devlibs/voxy-0.2.15-beta+1.20.1-legacyforge.jar`。返回 Voxy Distant 根目录编译，替换下面的绝对路径：

```sh
./gradlew test build -PvoxyJar=/绝对路径/voxy-0.2.15-beta+1.20.1-legacyforge.jar
```

Windows 将 `./gradlew` 换成 `./gradlew.bat`。后续 Gradle 命令同样需要 `-PvoxyJar=...`；也可将该属性放入本机 `~/.gradle/gradle.properties`。未设置时默认读取 `.materials/voxy-compat-build/versions/1.20.1-forge/build/devlibs/` 下的同名 JAR，此本地依赖不随仓库提供。

正式可安装 JAR 位于 `build/libs/`。生产依赖打包包含 RocksDB、Zstd 和 MixinExtras；Voxy 不随 Voxy Distant 打包。当前原生库裁剪目标为 Windows x64 和 Linux x64。

## 目录约定

```text
README.md          项目入口与安装
docs/              使用、开发、架构与精选验证报告
src/main/          生产源码与资源
src/test/          单元测试
src/smoke/         Forge/Voxy 集成与客户端测量
tools/             纳入版本控制的测试、基准脚本
gradle/            Gradle Wrapper
build.gradle       构建与开发启动配置
gradle.properties  仓库共享的 Gradle 参数
settings.gradle    项目与插件仓库设置
build/             标准构建输出和临时测试产物（忽略）
.run/              Gradle 开发游戏目录、EULA、配置（忽略）
.logs/             本地构建和集成检查日志（忽略）
.dist/             本地安装交付包（忽略）
.tools/            未纳入项目的本地分析脚本（忽略）
.materials/        本地参考源码与编译依赖（忽略）
.gradle/           Gradle 工作缓存（忽略）
.codegraph/        可选的本地代码索引（忽略）
```

`build/` 保留 Gradle 标准路径，避免改写构建工具与现有基准路径。其他本地产物使用点目录；不要再把构建日志写在根目录。游戏内部自己的 `logs/` 仍位于对应游戏目录内。

项目文档、测试源码和正式工具必须可以由 Git 正常发现，不要在 `.git/info/exclude` 中忽略整个 `docs/`、`src/test/`、`src/smoke/` 或 `tools/`。文档按主题维护，历史测试报告集中在 `docs/reports/` 并注明版本与适用条件，不把旧计划当作现状。

## 常规验证

```sh
./gradlew test build smokeClasses --console=plain
```

现有单测报告输出到 `build/reports/tests/test/`，运行工作目录为 `build/test-runtime/`，避免测试框架把日志写回根目录。无需为文档或目录整理新增业务单测；涉及游戏生命周期的改动应运行对应集成场景。

开发启动使用 `.run/`：`./gradlew runClient -PlodClient` 或 `./gradlew runServer`。首次运行服务端时，由操作者阅读并接受 Minecraft EULA；测试脚本只复制已有 `.run/eula.txt`，不会自动同意条款。

服务端集成检查需要 Python 3.11+，目前脚本的开发模块路径与部分工具按 Windows 环境编写：

```sh
./gradlew writeSmokeLaunch --console=plain
python tools/server-smoke.py --diagnostics
python tools/server-smoke.py --respawn
```

脚本输出 `.logs/integration-check.log`，临时环境在 `build/integration-*`。检查完成后停止相关进程，摘录结果并删除本轮临时环境。诊断检查包含故障注入，需以脚本断言和退出码判断结果，不能把预期错误日志单独当作失败。

真实 Voxy 数据与转换验证：

```sh
./gradlew runData -PclientSmoke --console=plain
```

## 真实客户端回归与基准

这些脚本依赖本地测试输入，普通克隆不自带游戏基线。复用已有 `build/benchmarks/lod-square256-cache128/`，不要复制用户真实存档。缺少基线时先准备独立测试客户端/服务端，不要把历史测量结果当作脚本已经运行。

生成最新启动参数：

```sh
./gradlew smokeClasses writeClientLaunch writeSmokeLaunch -PlodClient --console=plain
```

`build/client-launch.json` 和 `build/smoke-launch.json` 供生命周期脚本使用。其他历史基准读取基线目录内的 `client-launch.json`、`server-launch.json`；分别使用上述文件更新，服务端对应 `smoke-launch.json`。先完成普通服务端烟测，再重新生成带 `-PlodClient` 的参数，避免两种模式的开发 classpath 混用。

| 工具 | 输入与用途 |
| --- | --- |
| `tools/gameplay-lifecycle-check.py --case respawn` | 复用基线 `client/` 配置，创建小型世界；同维度/跨维度死亡重生 |
| `tools/gameplay-lifecycle-check.py --case recovery` | 同一小环境；索引重建及服务端导入后原地恢复 |
| `tools/gameplay-lifecycle-check.py --case local-cache` | 同一小环境；单人缓存重开与持久目录检查 |
| `tools/coarse-recovery-check.py --mode request --variant request-check --launch-build build` | `build/coarse-recovery-input/{client,server}`；真实接收流程检查 |
| `tools/coarse-recovery-check.py --mode travel --variant travel-cold --launch-build build` | 相同输入；空缓存高速往返 |
| `tools/coarse-recovery-check.py --mode extreme ...` | 相同输入；高速与未缓存区域瞬移，参数见[极端移动报告](reports/extreme-lod-recovery.md) |
| `tools/lod-performance-compare.py --baseline-build <旧版build目录>` | 相同小输入与独立旧版构建，交替 A/B 测量 |
| `tools/lod-live-benchmark.py` | 完整预生成基线、双端启动参数；传输测量及 `--settings-check` 等集成场景 |
| `tools/client-cpu-check.py` | 完整基线；暖缓存与 2,000 实体场景 |
| `tools/region-cpu-check.py --baseline <旧版源码目录>` | 旧版已编译类与客户端 classpath；区域算法微基准 |

小输入只需测试基线的客户端配置、服务端配置/EULA、`world/level.dat` 和原点四个 region：`r.-1.-1.mca`、`r.-1.0.mca`、`r.0.-1.mca`、`r.0.0.mca`，分别放入 `build/coarse-recovery-input/client/` 和 `server/` 对应位置。不复制完整 LOD 库；脚本会从小型世界导入。`-cold` 变体清理专用客户端缓存，极端场景的 `--uncached-target` 必须位于该测试世界尚无 region 文件的区域。

生命周期脚本统一使用 `build/gameplay-lifecycle/`，连续检查可复用，用完后清理。`lod-live-benchmark.py` 成功后默认删除游戏副本；失败现场和 `--retain-run-games` 保留的副本仍需显式清理。`RegionCpuCheck.java`、`ServerQueueCpuCheck.java`、`ServerSendCpuCheck.java` 是算法诊断工具，不能用其耗时替代真实客户端帧率或服务端 tick 测量。

## 测试产物管理

测试前核对已有环境和体积，每轮结束或中断后先停止本轮进程，再清理无后续用途的游戏副本、LOD 缓存、原始日志及录制。默认只保留简洁结果、必要截图或最小复现输入。

明确保留的基线或证据，应记录路径、用途、体积及清理条件；共享依赖、固定回归样本和真实存档不能按临时文件删除。`build/benchmarks/` 目前仍承载复用基线，因此执行 `gradlew clean` 前必须先检查其内容。新任务不要为每个案例复制完整环境。

### 当前本地保留产物

2026-10-09 清理历史运行、旧版对照构建、诊断记录和旧 JAR 后，`build/` 按文件长度合计从 **7,349,209,283 字节（6.84 GiB）**降至 **5,352,924,985 字节（4.99 GiB）**，净释放 **1,996,284,298 字节（1.86 GiB）**。这些本地产物不随 Git 提供；本轮仅清理与核验，没有重跑性能测试。

| 路径 | 当前体积 | 用途与清理条件 |
| --- | ---: | --- |
| `build/benchmarks/lod-square256-cache128/` | 5,195,696,595 字节 | 唯一预生成服务端世界/LOD、客户端配置及启动参数；多个正式回归脚本仍使用，停止使用或换基线后清理 |
| `build/libs/`、`build/devlibs/` | 16,644,705 / 16,642,918 字节 | 各保留一份 0.2.27 安装包/开发包，下次构建覆盖；删除旧版本和旧校验文件 |
| `build/cold-client-transfer-evidence/` | 3,286,357 字节 | 七轮传输结果、配置与两张代表截图；首页数字被新测量替代或核对结束后清理 |
| `build/coarse-recovery-evidence/`、`build/extreme-lod-evidence/` | 2,465,731 / 2,252,741 字节 | 粗远景与极端移动修复的最小证据；核对结束或新回归替代后清理 |
| `build/lod-performance-evidence/` | 170,878 字节 | 0.2.26 → 0.2.27 对照摘要；核对结束或新测量替代后清理 |
| 其余标准 Gradle 输出、开发启动参数及最新测试报告 | 118,407,060 字节 | 复用编译、打包和启动环境；可再生成，需要全量重建时按需清理 |

清理前后，基线、配置、启动参数和最新双包共 1,023 个文件的 SHA-256 一致；精简保留的 72 份证据文件也逐一核验。历史 `live/`、47 个旧集成目录、25 个旧对照/诊断目录和 12 份旧版本包/校验文件已删除。本轮简洁清理结果另记于本地 `.logs/build-cleanup.md`。
