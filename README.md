# Noxy / voxy-distant

Noxy 是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，它让 Voxy 除了在本地生成 LOD，还能从多人服务器接收预先生成或缓存的远景。并且能让客户端按距离接收所需精度的LOD，远的只接受低精度LOD，靠近时再补齐高精度LOD；这样减少了需要下载的数据量和服务器出口流量：载荷**降低十倍**的同时保持画面质量基本不变（只要你不放大看🤓）。让 Voxy 远景在多人服务器上实际可用。

Voxy 的 LOD 分为 L0～L4，每个体素的边长分别约为 1、2、4、8、16 个方块。默认设置 `32:0,64:1,96:2`：近处约 32 区块传 L0，中距离传 L1，更远处传 L2；由近到远接受的LOD精度逐渐降低，远处少传看不清的小方块，多列 LOD 再合并压缩，最大限度在保留画面表现的基础上，节省传输带宽。

## 传输表现

测试场景：Forge 1.20.1 服务端，预生成了半径 **256 区块**内的地形和完整 L0～L4 远景 LOD，带宽上限 30 Mbps。

接收客户端是无LOD缓存，角色站在原地不动，接收半径 **256 区块**内的 **205,548** 列 LOD 远景。

| 同一半径 256 区块范围 | 数据量 |
| --- | ---: |
| 服务端缓存中的完整 LOD 数据（205,548 列） | **1,500.46 MB** |
| Noxy 默认设置下，客户端实际收到的 LOD 载荷 | **117.93 MB** |
| 相比完整 LOD，减少的流量载荷 | **1,382.53 MB（92.14%）** |

表中服务端数据量是这 **205,548列** 完整 LOD 在硬盘上占用的空间。如果把这些数据直接发给玩家，就需要约 1,500.46 MB；Noxy 优化下，本轮实际只发了 117.93 MB，按此口径每位玩家少用约 **1,382.53 MB（92.14%）** 的 LOD 流量载荷。
LOD 从首片传输到末片约 **62.34 秒**，平均载荷吞吐 **15.13 Mbps**；客户端应用速度约 **3,287 列/秒**。

[完整测试条件与结果](docs/reports/cold-client-transfer.md)。

## 安装

支持 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21、Windows/Linux x64**。

| 安装位置 | 所需 Mod |
| --- | --- |
| 客户端 | [Noxy](https://github.com/KNaiFen/noxy/releases)、[Voxy Forge 0.2.15-beta](https://github.com/KNaiFen/voxy-forge-1.20.1/releases/tag/forge-1.20.1-win-linux-x64-upstream-9a2110caa657)、[Embeddium 0.3.31](https://modrinth.com/mod/embeddium/version/UTbfe5d1) |
| 专用服务器 | Noxy；无需安装 Voxy 或 Embeddium |

把安装版 JAR 放入游戏或服务器的 `mods/`。Voxy 选择不带 `-dev` 后缀的安装包。多人远景传输需要服务器也安装兼容的 Noxy；客户端仍可连接没有 Noxy 的服务器，但不能从中下载服务端远景。

进入游戏后，在原版“选项”页点击 **VD** 按钮。配置文件为 `config/voxy_distant.toml`；OP 可在 VD 页面远程调整服务器设置。

## 功能与当前状态

- 单人世界在玩家周围生成远景，复用已经保存的完整 LOD。
- 多人服务端缓存 L0～L4，按距离选择精度并合批压缩；移动、转向和靠近时继续补齐。
- 支持已有世界导入、预生成、带宽与内存预算、服务端负载节流，以及客户端手动重建覆盖索引。
- 当前源码版本 **0.2.27-forge-1.20.1**，协议 **17**。已包含高速移动恢复、节点与缓存版本一致性，以及死亡重生、维护后原地恢复、单人缓存识别修复。源码状态与公开发行包应分别核对，下载版本以发行页为准。

Voxy 的 **Chunk Ingest（区块摄入）** 与 VD 的“接收服务端远景”独立。远程接收需要开启 Voxy 渲染；单人本地生成仍需开启区块摄入。

## 文档与开发

- [文档索引与当前验证状态](docs/README.md)
- [使用、配置与管理命令](docs/usage.md)
- [构建、测试与目录约定](docs/development.md)
- [代码结构与数据流程](docs/architecture.md)

源码构建使用 Java 21 和配套 Voxy 的开发版 JAR：

```sh
./gradlew build -PvoxyJar=/绝对路径/voxy-0.2.15-beta+1.20.1-legacyforge.jar
```

可安装 JAR 输出到 `build/libs/`。Windows 使用 `./gradlew.bat`；依赖的固定提交及完整步骤见开发指南。

## 致谢与许可

本项目为纯 **VibeCoding** 项目，遵循 [MIT 协议](LICENSE)。

感谢 [Codex](https://chatgpt.com/codex)、[Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) 和 [Voxy](https://github.com/MCRcortex/voxy)。
