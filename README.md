# Noxy / voxy-distant

Noxy 是 [Voxy](https://github.com/MCRcortex/voxy) 的附属 Mod，为 Minecraft 1.20.1 Forge 提供单人远景生成和多人服务器 LOD 传输。服务器缓存地形远景，客户端按距离接收所需精度，靠近后补齐细节；渲染由配套 Voxy 完成。

## 安装

支持 **Minecraft 1.20.1、Forge 47.4.0～47.x、Java 21、Windows/Linux x64**。

| 安装位置 | 所需 Mod |
| --- | --- |
| 客户端 | [Noxy](https://github.com/KNaiFen/noxy/releases)、[Voxy Forge 0.2.15-beta](https://github.com/KNaiFen/voxy-forge-1.20.1/releases/tag/forge-1.20.1-win-linux-x64-upstream-9a2110caa657)、[Embeddium 0.3.31](https://modrinth.com/mod/embeddium/version/UTbfe5d1) |
| 专用服务器 | Noxy；无需安装 Voxy 或 Embeddium |

把安装版 JAR 放入游戏或服务器的 `mods/`。Voxy 选择不带 `-dev` 后缀的安装包。多人远景传输需要服务器也安装兼容的 Noxy；客户端仍可连接没有 Noxy 的服务器，但不能从中下载服务端远景。

进入游戏后，在原版“选项”页点击 **VD** 按钮。配置文件为 `config/voxy_distant.toml`；OP 等级 2 可在 VD 页面调整服务器设置。

## 功能与当前状态

- 单人世界在玩家周围生成远景，复用已经保存的完整 LOD。
- 多人服务端缓存 L0～L4，按距离选择精度并合批压缩；移动、转向和靠近时继续补齐。
- 支持已有世界导入、预生成、带宽与内存预算、服务端负载节流，以及客户端手动重建覆盖索引。
- 当前源码版本 **0.2.27-forge-1.20.1**，协议 **17**。已包含高速移动恢复、节点与缓存版本一致性，以及死亡重生、维护后原地恢复、单人缓存识别修复。源码状态与公开发行包应分别核对，下载版本以发行页为准。

Voxy 的 **Chunk Ingest（区块摄入）** 与 VD 的“接收服务端远景”独立。远程接收需要开启 Voxy 渲染；单人本地生成仍需开启区块摄入。

## 传输表现

0.2.24 的一次本机空缓存对照中，接收半径 256 区块、205,548 列远景：完整服务端缓存为 **1,500.46 MB**，实际 LOD 载荷为 **117.93 MB**，按该口径减少 **92.14%**；首片至末片 **62.34 秒**，平均载荷吞吐 **15.13 Mbps**。

这是 Ryzen 7 9700X、预生成世界、30 Mbps 限额下的历史结果，不代表当前版本的公网速度、首次世界生成速度或帧率收益。[测试条件与完整数据](docs/reports/cold-client-transfer.md)；[0.2.27 修复的性能成本](docs/reports/lod-fix-performance.md)。

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
