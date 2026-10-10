# 代码结构与数据流程

Noxy 负责远景生成、缓存、传输和与 Voxy 的衔接；体素渲染器由配套 Voxy 提供。当前平台是 Minecraft 1.20.1 Forge，Java 21。

## 源码分工

生产源码位于 [`src/main/java/dev/voxydistant/`](../src/main/java/dev/voxydistant)。

| 模块 | 职责与入口 |
| --- | --- |
| `client/` | `ClientEvents` 接入生命周期与界面；`RemoteClient` 管理接收会话；`RegionDiscovery`、`RetryQueue` 处理范围发现与重试；`CacheIndexRebuild` 提供手动索引重建 |
| `server/` | `RemoteServer` 管理玩家需求、缓存工作、发送和维护；`PendingRequests`、`PriorityState` 排序；`LoadGovernor` 限负载；`RegionNbtImporter` 读取已有区块 |
| `generation/` | `GenerationController` 控制单人生成；`LocalDiscovery` 发现缺失列；`ChunkSnapshot` 在主线程捕获区块数据 |
| `data/` | `LodColumn` 表示列，`ColumnConverter` 转换 L0～L4；`ColumnCodec`、`BatchCodec` 编解码；`LodDatabase` 保存服务端缓存与元数据 |
| `network/` | `Protocol` 定义协议 17，`ByteBudget` 管理字节预算，`ConnectionAccess` 衔接连接 |
| `movement/` | `MovementPrediction`、`RequestShape` 计算移动预测和请求形状 |
| `compat/` | `VoxyBridge`、`CoarseLodReceiver` 写入 Voxy，`CoverageStore` 记录精度/版本/持久覆盖；Mixin 衔接节点、网格、原版近景与保存 |
| `config/` | `DistantConfig` 定义结构 7 配置，`DistanceBands` 选择精度，`ServerSettings` 用于在线服务端设置 |

## 多人远景

1. 服务端发送世界标识、维度、高度、半径与距离档位，客户端建立对应会话。
2. 客户端结合持久覆盖目录、服务器区域信息和移动预测发现缺失或精度不足的列，携带请求 ID、版本和接收信用请求数据。
3. 服务端合并同列需求。缓存有效时读取需要的层级；缺失或过期时，在配置允许的范围内加载区块、捕获快照、转换并保存。
4. 服务端按玩家距离选择精度、合批压缩，受全服/玩家带宽、发送内存和客户端信用限制发送分片。
5. 客户端有序解压、映射、写入 Voxy，更新覆盖与网格状态，再释放接收信用；靠近后继续请求更细层级。

传输回执只表示接收缓冲可释放，不等于网格已上传 GPU 或数据已经持久化。请求 ID、会话 epoch、列版本与网格 generation 分别约束不同阶段的过期结果，不能互相替代。

## 线程与生命周期

服务器主线程拥有活区块、ticket、玩家会话和调度；快照按预算捕获。数据库、转换与编码工作放在后台，完成结果回到服务端线程。客户端接收与请求由有序后台工作通道负责，主线程发布状态并捕获原版近景。

单人生成复用后台转换线程池读取持久覆盖目录，目录未就绪时不会把整区误判为缺失；完整列从候选中排除。登录、断开、切维度、死亡重生及维护恢复都需要处理对应会话和发现状态。

服务端 `LodDatabase` 和客户端 `CoverageStore` 是不同存储。覆盖索引记录可证实的精度与版本，不能用“内存里没有覆盖页”推断磁盘没有 LOD。启动时不做全库重建，旧目录按需迁移，显式重建由客户端命令触发。

## 测试边界

[`src/test/`](../src/test) 包含目录、范围、移动预测及请求排序等现有单测；[`src/smoke/`](../src/smoke) 包含真实 Forge/Voxy 集成、客户端控制和测量探针。后者独立于生产源集，发行 JAR 不应包含 smoke 类。运行入口和基线要求见[开发指南](development.md)。
