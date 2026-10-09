# 正常游玩问题逐项修复验证

对应 [审查报告](gameplay-bug-review-2026-10-09.md)。基线 `cad17d9`，Minecraft 1.20.1 / Forge 47.4.20 / Java 21，复用已有 Gradle、Voxy 与客户端启动依赖。

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
