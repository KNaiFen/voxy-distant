# 使用与配置

安装依赖见[项目首页](../README.md)。游戏内入口为原版“选项”页的 **VD** 按钮，客户端和服务端均使用各自游戏目录下的 `config/voxy_distant.toml`。

## 三种使用场景

| 场景 | 行为与前提 |
| --- | --- |
| 单人世界 | 开启 VD 本地生成、Voxy 渲染与 Chunk Ingest，在玩家周围提前生成 LOD；已有完整持久缓存不会再次排入生成候选 |
| 安装 Voxy Distant 的多人服务器 | 开启 VD 接收与 Voxy 渲染，按服务器权限范围下载远景；Chunk Ingest 可关闭，接收会话自行采集原版近景 |
| 未安装 Voxy Distant 的服务器 | 无服务端远景下载；本地 Voxy 可继续记录客户端实际加载过的地形 |

0.2.24～0.2.25 曾错误地要求开启 Chunk Ingest 才能建立远程会话；当前源码已修复。需要高速移动、节点恢复及重生修复时，应同时更新客户端与服务器到包含相应修复的版本。

## 常用配置

以下为当前代码默认值。距离以**区块**为单位，`Mbps` 为十进制兆比特/秒，`KiB`、`MiB` 为二进制字节单位。详细字段、合法范围和预设以配置文件注释及 [`DistantConfig`](../src/main/java/dev/voxydistant/config/DistantConfig.java) 为准。

| 配置键 | 默认值 | 用途 |
| --- | --- | --- |
| `client.receiveServerLods` | `true` | 接收服务端 LOD |
| `client.radius` | `0` | 0 跟随 Voxy 渲染距离；实际接收仍受服务端半径限制 |
| `client.downloadKiBPerSecond` | `0` | 0 不额外限制客户端下载速率 |
| `client.receiveMemoryMiB` | `128` | 接收处理内存预算 |
| `client.indexMemoryMiB` | `256` | 覆盖索引内存预算 |
| `client.requestWindowColumns` | `256` | 未完成列请求窗口 |
| `client.localGeneration.enabled` | `true` | 单人本地远景生成 |
| `client.localGeneration.radius` | `128` | 单人生成半径 |
| `client.localGeneration.preset` | `BALANCED` | 本地生成负载预设 |
| `server.enabled` | `true` | 提供服务端远景 |
| `server.radius` | `256` | 允许接收的最大半径 |
| `server.generateMissingChunks` | `true` | 允许为缺失远景加载/生成区块 |
| `server.distanceBands` | `["32:0", "64:1", "96:2"]` | 按距离选择传输精度 |
| `server.totalBandwidthMbps` | `30.0` | 全服 LOD 载荷带宽上限 |
| `server.playerBandwidthKiBPerSecond` | `0` | 0 不额外设置单玩家带宽上限 |
| `server.playerSendMemoryMiB` / `server.totalSendMemoryMiB` | `32` / `64` | 单玩家/全服发送内存预算 |
| `server.generation.preset` | `CUSTOM` | 服务端负载预设 |
| `server.generation.automaticThrottle` | `true` | 根据服务端负载降速、暂停及恢复 |
| `debug.enabled` / `debug.verbose` | `false` / `false` | 诊断汇总/详细日志，常规游玩保持关闭 |

LOD 层级为 L0～L4，对应约 1、2、4、8、16 方块边长的体素。默认近处使用 L0，中距离 L1，更远使用 L2；超过最后一档仍沿用 L2，接收范围由半径限制。档位按兄弟节点组对齐，边界不是逐列精确的同心圆。

OP 等级 2 可在 VD 服务端设置页在线修改支持的字段。直接修改服务器配置文件后使用 `/voxydistant reload`；客户端配置优先通过 VD 页面修改。不要将历史基准的调参组合直接当作多人服推荐值。

## 命令

| 命令 | 执行侧与权限 | 行为 |
| --- | --- | --- |
| `/voxydistant rebuildindex` | 客户端，无需 OP | 后台重建当前连接、当前维度的 Voxy 覆盖索引 |
| `/voxydistant stats` | 服务端，OP 2 | 查看缓存、队列、负载及维护任务状态 |
| `/voxydistant import` | 服务端，OP 2 | 从已有世界 region 文件导入远景，扫描已保存维度；独占导入期间暂停远景服务，结束后恢复 |
| `/voxydistant pregen <radius> <x> <z>` | 服务端，OP 2 | 在命令所在维度，以中心**区块坐标**预生成；半径 1～2048 区块 |
| `/voxydistant reload` | 服务端，OP 2 | 重新读取并应用服务端及诊断配置 |

例如 `/voxydistant pregen 64 0 0` 表示以区块 `(0,0)` 为中心、半径 64 区块。已有维护任务运行时不会再启动第二个导入或预生成任务。当前没有单独的 `/voxydistant cancel` 命令。

关闭 `generateMissingChunks` 不会凭空提供未缓存地形；旧缓存已失效且对应区块未加载时，也可能暂时返回无数据。需要已有世界的远景时，可先导入再开放接收。服务器缓存位于存档的 `data/voxy-distant/`，客户端 LOD 保存在 Voxy 对应世界存储中；仓库的 `.run/`、`.logs/` 只是开发环境约定。

## 手动重建覆盖索引

客户端和服务端均不在启动时全盘重建索引。已有 Voxy 缓存但覆盖索引缺失时，进入目标服务器或单人世界、开启 Voxy 后执行 `/voxydistant rebuildindex`。命令强制扫描当前 Voxy 存储，输出进度与结果；其他连接、存档和维度不参与。

切换维度、断开连接或关闭 Voxy 会取消任务，再次执行从头开始。已有可信覆盖、服务器版本与进行中的写入会保留；原生 Voxy 数据无法恢复服务器版本，也无法区分未摄取的空气，未知数据仍需向服务器核验。配套 Voxy 的 LMDB/Redis 后端未实现所需位置枚举，命令会明确失败；后台扫描仍会消耗 CPU、磁盘带宽及与 LOD 键数相关的内存。

当前源码已修复重建或导入结束后静止玩家不恢复扫描的问题，无须靠移动来触发恢复。详细验收见[游玩生命周期报告](reports/gameplay-lifecycle.md)。
