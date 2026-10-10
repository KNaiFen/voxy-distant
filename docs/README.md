# 项目文档

本目录维护当前可用的项目说明，以及仍有参考价值的验证报告。历史方案、已被修复记录替代的审查、重复的发布及清理流水账可从 Git 历史查阅。

## 使用与开发

| 文档 | 内容 |
| --- | --- |
| [项目首页](../README.md) | 简介、安装依赖、功能与下载 |
| [使用指南](usage.md) | 开关、配置默认值、导入、预生成、索引重建 |
| [开发指南](development.md) | 编译依赖、测试入口、目录与产物管理 |
| [架构说明](architecture.md) | 模块职责、数据流程、线程和缓存边界 |

## 当前源码状态

核对日期：**2026-10-10**；生产代码基线：`40da0a7`。版本 **0.2.27-forge-1.20.1**，网络协议 **17**，配置结构 **7**。版本分别以 [`build.gradle`](../build.gradle)、[`mods.toml`](../src/main/resources/META-INF/mods.toml)、[`Protocol`](../src/main/java/dev/voxydistant/network/Protocol.java) 和 [`DistantConfig`](../src/main/java/dev/voxydistant/config/DistantConfig.java) 为准。

最新发行包为 [0.2.27](https://github.com/KNaiFen/noxy/releases/tag/0.2.27)，包含上述生产代码及目录整理；安装包身份与发行验证见[0.2.27 发行核验](reports/release-0.2.27.md)。

| 已完成修复 | 提交 | 验证 |
| --- | --- | --- |
| 高速移动后粗远景无法恢复、摄入与接收错误绑定 | `dd21d22` | [粗 LOD 恢复](reports/lod-recovery.md) |
| 极端移动下父节点状态、加载标记和缓存版本失配 | `69c9883` | [极端移动回归](reports/extreme-lod-recovery.md) |
| 死亡重生后仍使用旧玩家或维度会话 | `aecbabb` | [游玩生命周期](reports/gameplay-lifecycle.md) |
| 索引重建或导入结束后静止玩家不继续扫描 | `c498d00` | [游玩生命周期](reports/gameplay-lifecycle.md) |
| 单人生成把未载入内存的持久 LOD 当作缺失 | `40da0a7` | [游玩生命周期](reports/gameplay-lifecycle.md) |

最近一次业务修复验证包含 17 项现有单测、正式构建、真实客户端重生/恢复/缓存重开，以及 Forge 中的持久覆盖目录检查。具体场景和证据范围见报告；不代表所有整合包、光影或多人负载组合都已覆盖。

## 保留的验证报告

| 报告 | 测试版本与日期 | 保留原因 |
| --- | --- | --- |
| [空缓存传输](reports/cold-client-transfer.md) | 0.2.23 → 0.2.24，2026-10-04 | 首页性能数字的来源与限制 |
| [粗 LOD 恢复](reports/lod-recovery.md) | 0.2.26，2026-10-07 | 接收、精度补齐和扫描掩码回归 |
| [极端移动恢复](reports/extreme-lod-recovery.md) | 0.2.27 / `69c9883`，2026-10-07 | 节点、版本与空缓存极端移动回归 |
| [修复性能对照](reports/lod-fix-performance.md) | `dd21d22` → `69c9883`，2026-10-07 | 量化正确性修复的客户端与服务端成本 |
| [游玩生命周期](reports/gameplay-lifecycle.md) | 截至 `40da0a7`，2026-10-09 | 三项最新问题的复现、修复和验证 |
| [0.2.27 发行核验](reports/release-0.2.27.md) | 0.2.27，2026-10-10 | 当前发行安装包身份、验证命令与产物管理 |

报告中的路径、哈希和统计描述对应测试当时的版本。Git 不包含大型测试环境或原始录制；复测准备以[开发指南](development.md)为准，不能把历史报告的 JAR 哈希当作当前构建哈希。
