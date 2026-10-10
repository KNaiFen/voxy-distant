# 0.2.27 发行核验

日期：2026-10-10；发行标签 `0.2.27`，基于 `main`，生产代码基线 `40da0a7`。完整同步 `dev` 的提交与目录整理后发布，不增加业务代码。Minecraft 1.20.1 / Forge 47.4.x / Java 21，Windows 与 Linux x64。

## 安装包身份

- 安装文件：`voxy-distant-0.2.27-forge-1.20.1.jar`，16,644,705 字节。
- SHA-256：`b82034b77e326e2725722203bbcbd25eed0eb472777d37d422f9d96ce26dad22`。
- 校验文件：`voxy-distant-0.2.27.sha256`，104 字节。
- 网络协议 17、配置结构 7；缓存格式未改变，升级无需清空已有 LOD。

## 验证

- 同步 `main` 时执行 `./gradlew test build smokeClasses --offline --console=plain` 成功；`./gradlew test --rerun --offline --console=plain` 实际重跑 17 项单测，0 失败、0 错误、0 跳过。在线验证曾因 NeoForge 依赖站连接超时失败，使用已有依赖缓存完成验证。
- 发行打包执行 `./gradlew jar --rerun reobfJar --rerun --offline --console=plain` 成功，重新生成可安装 reobf JAR。
- 校验安装 JAR 及三个内嵌依赖的 ZIP CRC；核对 `mods.toml` 版本、Java 21 class 版本、Mixin 配置、类与 Refmap。安装包不含 smoke 测试类。
- 内嵌依赖为 RocksDB 10.2.1、Zstd 1.5.7-6、MixinExtras 0.5.4；核对裁剪后的 Windows/Linux x64 原生库。
- 真实游戏回归沿用已提交的[粗远景恢复](lod-recovery.md)、[极端移动恢复](extreme-lod-recovery.md)与[游玩生命周期](gameplay-lifecycle.md)报告；本次发行没有重新运行游戏场景。
- 性能代价与测量限制见[节点修复性能对照](lod-fix-performance.md)，不将正确性修复描述成零开销或新增性能收益。

## 产物管理

复用原有构建缓存，没有创建客户端、服务端副本或测试存档。打包任务已结束，原始构建日志在核验后删除；只保留本报告和标准最新构建、测试输出。

本次打包前后，`build/libs/` 的安装 JAR 和 `build/devlibs/` 的开发 JAR 仍各一份，分别为 16,644,705 / 16,642,918 字节；校验文件另增加 104 字节。发行 JAR 用于安装与上传，下一版构建覆盖；标准单测结果和报告供核验，下次测试覆盖。原有游戏基线、依赖与证据保持不变，其用途和清理条件见[开发指南](../development.md)。

消融复核：版本已在 `dev` 更新为 0.2.27；本次只补充发行身份与验证入口，没有新增生产逻辑、抽象或测试。
