# 正常游玩问题逐项修复验证

对应 [审查报告](gameplay-bug-review-2026-10-09.md)。基线 `cad17d9`，Minecraft 1.20.1 / Forge 47.4.20 / Java 21，复用已有 Gradle、Voxy 与客户端启动依赖。

三项按顺序测试、修复、分别提交：VD-01 为 `aecbabb`，VD-02 为 `c498d00`，VD-03 为本文最后一节所在提交。下文区分真实客户端工作流与在真实 Forge 环境中隔离覆盖页状态的集成验收；没有测量 FPS/TPS 改善幅度。

## VD-01：死亡重生会话

- 修复前：新增服务端集成验收，实际调用 Forge `PlayerList.respawn()`；主世界重生触发 `Respawn retained old LOD player/dimension: minecraft:overworld`，退出码 1。
- 修复：服务端监听 `PlayerRespawnEvent` 并重建玩家远景会话。客户端监听 `ClientPlayerNetworkEvent.Clone` 关闭旧接收会话，避免同维度重生保留 ClientLevel、相同 Hello 被忽略时沿用旧请求。
- 服务端验收：`./gradlew writeSmokeLaunch --console=plain`；`python tools/server-smoke.py --respawn`。主世界→主世界、下界→主世界均通过；确认新玩家引用、新维度 Hello 和出生点附近请求可入队。
- 实机验收：`./gradlew writeClientLaunch smokeClasses -PlodClient --console=plain`；`python tools/gameplay-lifecycle-check.py --case respawn`。真实客户端连接本机服务端，接收半径 8，原版视距 2；移动至 1024 格外死亡回出生点，新 epoch 从 1 增至 2；下界死亡回主世界后 Hello/当前维度一致，接收 72 列，请求归零。
- 消融复核：仅增加两个生命周期监听，没有新协议或额外状态。保留独立的重生集成验收及可复用的真实客户端脚本。
- 清理：测试进程全部停止；删除专用集成场景 12,066,599 字节和本轮实机原始结果 1,352,679 字节。暂保留 `build/gameplay-lifecycle/server`（21,220,997 字节）及 `client`（14,239,081 字节），供紧接着的 VD-02/VD-03 复用；三项验收结束后删除。未修改已有 4.8 GiB 游戏基线或真实存档。

## VD-02：静止恢复扫描

- 修复前实机复现：`./gradlew smokeClasses -PlodClient --console=plain`；`python tools/gameplay-lifecycle-check.py --case recovery`。真实客户端执行 `/voxydistant rebuildindex`，保持位置不变；区域数由 4 降为 0，generation 保持 1，中心为 `Integer.MIN_VALUE`，验收断言失败。
- 修复：在现有范围更新判断中加入 `s.x == Integer.MIN_VALUE`；只改一行，让维护重置和索引重建共用的失效标记触发下一次扫描。
- 修复后实机验收：`./gradlew classes --console=plain` 后重跑同一脚本。索引重建后区域数为 4、generation 1→2；继续执行真实服务端 `voxydistant import`，结束后客户端原地恢复 4 个区域、generation 5，并向服务端发送新 epoch 的范围请求。全部通过。
- 消融复核：复用原有失效中心标记，没有增加刷新开关、轮询计时器或重复重置代码。
- 清理：两轮实机进程均已停止，分别删除失败/通过运行的原始结果 549,283 / 542,003 字节。仅继续保留 VD-03 将复用的场景：`build/gameplay-lifecycle/server` 19,602,159 字节、`client` 10,697,940 字节；下一项结束后统一删除。

## VD-03：单人生成识别磁盘覆盖

- 修复前对照：半径 8、原版视距 2、关闭移动预测，真实单人世界首次完成 172 列；同进程退出再进入时没有重复提交。这个小范围的覆盖页已被渲染器载入，因此不能把“重进存档必然复现”作为结论。
- 确定性复现：在真实 Forge 单人服务端线程上，创建并保存 RocksDB 覆盖记录，关闭重开，以没有渲染器预热的真实 `WorldEngine` 调用生产 `GenerationController.Session.refill()`。修复前完整缓存仍产生 `candidates=172 renderPages=0`，触发 `Persisted local coverage scheduled again`，脚本退出码 1。
- 修复：本地发现先在现有后台工作池读取持久化区域目录，最多保留 4 个目录任务；目录未就绪时不把该区域当作缺失。完整列从候选中排除，现有内存检查继续识别本轮刚完成的列。区域新增或重新进入的列会刷新目录，避免重复使用生成前的目录快照。旧格式覆盖页沿用已有逐页迁移逻辑，没有在服务端 tick 中逐列同步读盘。
- 高度处理：提取覆盖库已有的高度设置，单人会话按实际维度高度初始化目录；不改变本地/远端模式、内存预算或存储格式。

验证命令：

```sh
./gradlew test build smokeClasses -PlodClient --console=plain
python tools/gameplay-lifecycle-check.py --case local-cache
python tools/gameplay-lifecycle-check.py --case recovery
```

| 场景 | 修复后结果 |
| --- | --- |
| 完整主世界覆盖，覆盖页未载入 | 候选 0，渲染页 0 |
| 缺失整列 `(-8,0)`、另一列 `(8,0)` 仅缺顶层 section | 仅这两列成为候选，其余完整列不提交 |
| 同一缺失场景移除旧目录，保留原覆盖数据 | 迁移 8 个垂直页后仍仅这两列成为候选，渲染页 0 |
| 下界实际高度 `[0,16)` | 仅两列真实缺失成为候选，本地模式保持不变 |
| 两列补全并保存，重新打开覆盖库，再移出/返回原范围 | 不再产生候选；同时验证清空发现状态后重新建立范围，渲染页仍为 0 |
| 实际单人存档生成、退出、重进 | 缺失范围正常完成 172 列；重进后 submitted=0、checks=0、状态“范围内已完成” |
| 清空本轮临时环境后独立启动脚本 | 能自动建立专用小型世界，完整单人验收通过，不依赖前两项的遗留存档 |
| 覆盖库共用代码的远端回归 | `rebuildindex` 后区域数 4、generation 1→2；真实 import 结束后 generation 5，静止恢复通过 |

集成检查输出 `LOCAL_COLD_CACHE_PASS`；主世界和下界分别输出 `LOCAL_COLD_CACHE_REENTRY_PASS`。其中冷页检查验证的是生产候选调度，不把隔离环境说成实际发生了 172 次区块加载；实际正常生成和重进由同一脚本的客户端工作流验证。

消融复核：保留必要的区域目录准备和每区域完整列位图，复用转换线程池与既有覆盖目录；没有全盘预热、额外线程池、新协议、无限目录缓存或改变 `hasColumn()` 的所有调用者。原有范围算法单测只适配准备回调，新问题通过可重复的游戏集成场景验证。

## 最终验证与清理

- `./gradlew test build smokeClasses -PlodClient --console=plain` 成功，17 项单测通过，0 失败、0 错误、0 跳过；生成正式 reobf JAR。编译仍有已有 Forge API 弃用提示。
- 每轮运行结束均先停止游戏进程，再删除不再需要的原始结果。VD-03 前几轮分别清理 235,784、248,168、252,314 字节，远端回归清理 685,043 字节；为验证独立启动，先删除已用完的共享小环境 51,364,431 字节，再建立唯一一套新场景；独立启动的原始结果清理 288,199 字节。
- 最后复测通过后，确认没有遗留游戏 Java 进程；删除 `build/gameplay-lifecycle` 全部 21,041,949 字节（客户端 12,244,086、服务端 8,554,048、最后结果 243,815），目录已不存在。前两项提到的“暂保留”环境至此全部清理，集成测试自身的临时 RocksDB 也在每例结束时关闭并删除。
- 保留正式回归源码、本文及标准构建输出，没有保留本轮游戏环境或原始日志副本。标准最新测试结果占 8,057 字节、HTML 报告 37,873 字节；本次正式 JAR 为 `build/libs/voxy-distant-0.2.27-forge-1.20.1.jar`，16,644,705 字节，供安装或后续发布使用，下次构建覆盖即可。未删除已有版本 JAR、共享依赖、历史基线或用户存档。
