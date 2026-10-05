# Create 机壳内部部件剔除可行性（2026-10-05）

用户确认当前后端为 `flywheel:instancing`。本次完成源码、已有客户端 JAR、模型和配置核查，尚未做游戏内性能对照。成功标准是找出能减少实际模型提交、不会误删可见轴端和齿轮窗口、且剔除判断不会增加大量逐帧 CPU 工作的路径。

## 已核实的机制

- Create 将静态机壳与旋转部件分别绘制；包壳轴仍创建完整 SHAFT 模型，包壳齿轮仍创建旋转齿轮模型，轴端仅按 `hasShaftTowards` 单独决定是否创建。普通方块面剔除不会自动删除这些实例。[轴 visual](https://github.com/Creators-of-Create/Create/blob/338bfa0aec952fa51656e8f61bd621ca9b3b2e00/src/main/java/com/simibubi/create/content/kinetics/base/SingleAxisRotatingVisual.java)、[齿轮 visual](https://github.com/Creators-of-Create/Create/blob/338bfa0aec952fa51656e8f61bd621ca9b3b2e00/src/main/java/com/simibubi/create/content/kinetics/simpleRelays/encased/EncasedCogVisual.java)。
- `instancing` 最终以 instancer 的实例数提交模型，没有 indirect 的逐实例深度金字塔剔除。深度测试会阻止被机壳挡住的像素写入，但不等于省掉实例提交与顶点处理。[InstancedDraw](https://github.com/Engine-Room/Flywheel/blob/f3029f6373a25c7f4e8565c5f6931b167fc63ee9/common/src/backend/java/dev/engine_room/flywheel/backend/engine/instancing/InstancedDraw.java)。
- Flywheel 已有 `instance.handle().setVisible(false/true)`。在 instancing 中隐藏会从活动实例列表移除，恢复时重新加入；可复用现有实例，不必每次销毁、重建整个 visual。此 API 与实现已在本地 Flywheel 1.0.4 字节码中核实。[InstanceHandle](https://github.com/Engine-Room/Flywheel/blob/f3029f6373a25c7f4e8565c5f6931b167fc63ee9/common/src/api/java/dev/engine_room/flywheel/api/instance/InstanceHandle.java)、[BaseInstancer](https://github.com/Engine-Room/Flywheel/blob/f3029f6373a25c7f4e8565c5f6931b167fc63ee9/common/src/backend/java/dev/engine_room/flywheel/backend/engine/BaseInstancer.java)。
- 包壳轴、包壳齿轮通过 `encasedBase` 设置 `noOcclusion`；Entity Culling 判断完整遮挡块依赖 `isSolidRender`，因此不会把这些机壳当成不透明整方块。这是模型留孔情况下的合理保守行为。[Create 注册](https://github.com/Creators-of-Create/Create/blob/338bfa0aec952fa51656e8f61bd621ca9b3b2e00/src/main/java/com/simibubi/create/foundation/data/BuilderTransformers.java)、[Entity Culling Provider](https://github.com/tr7zw/EntityCulling/blob/ae786c8921de5ea52c06481dfa47180e42bbd54d/src/main/java/dev/tr7zw/entityculling/Provider.java)。
- 本地 Create 6.0.6 原始资源中，安山岩与黄铜齿轮机壳侧纹理均有 48/256 像素完全透明；传动轴延伸到方块两端。不能把“包壳”直接等同于“所有旋转模型不可见”。材质包和附属模组也可能改变开孔与模型。
- VD 的 `SolidBoundsMixin` 处理 Voxy 远景使用的区块深度盒，不负责 Create/Flywheel 实例；现有 LOD 索引不能直接充当近景机器的剔除结果。

## 可行方案与取舍

| 方案 | 对当前 instancing 的作用 | 成本与限制 |
| --- | --- | --- |
| 针对部件的局部封闭判断，切换实例可见性 | 最适合处理确定完全藏在机壳内部的轴和小齿轮 | 只在相关方块变化时重算；需要明确模型开孔和旋转扫掠范围 |
| Entity Culling 结果接入 Flywheel | 适合整台机器被普通不透明墙体遮挡 | 额外 CPU 遮挡计算；整方块 AABB 无法识别同一格内的机壳自遮挡 |
| 保留外露部分、精简内部模型 | 可以降低仍需显示轴端的模型顶点数 | 不一定减少实例或 draw call；拆成多个实例可能得不偿失 |
| 改为 indirect 使用 GPU Hi-Z | 适合较大范围墙体遮挡 | 改变当前后端；保守包围球和开孔仍会保留机壳内部实例，不能保证解决自遮挡 |

推荐首个原型采用局部封闭判断，不逐帧对全部机器发射射线。只支持模型已核实的安山岩/黄铜包壳轴和小齿轮；仅当从外部所有开口都不能看到旋转扫掠范围时隐藏对应实例。轴端开口被完整不透明邻块封住可以作为候选条件，但不能仅凭“连接了另一根轴”判定封闭。齿轮需逐一检查侧面窗口和启用的轴端；大齿轮可能伸到本格之外，不能套用同一格或简单六邻块规则。

在包壳、拆壳、轴方向/轴口状态、相关邻块变化和资源重载时重新判断，在 Flywheel 合适的更新阶段仅处理可见性状态变化。普通 BER 路径要使用同一规则，避免切换后端后失效。机械网络、服务端 tick 和传输保持原行为；不能因为不可见就取消机械运算。运动结构、Ponder、透明材质及摄像机进入壳内需要单独验证，不能沿用普通静态世界的坐标与封闭条件。

## 现成适配

[Create: Nowheel](https://modrinth.com/mod/create-nowheel) 已连接 Entity Culling 与 Flywheel。源码中，非 indirect 后端通过 `queueRemove/queueAdd` 移除、恢复整个 visual；indirect 则主要限制不可见 visual 的更新并依赖 GPU 剔除。[后端处理](https://github.com/lap2ka/nowheel/blob/1896ddf2f6ee46bd5ab7aaff39e15ea834f6768d/src/main/java/dev/lapt/nowheel/core/util/LegacyCullingUtil.java)、[更新调度](https://github.com/lap2ka/nowheel/blob/1896ddf2f6ee46bd5ab7aaff39e15ea834f6768d/src/main/java/dev/lapt/nowheel/mixin/flywheel/StorageMixin.java)。

它值得作为外部墙体遮挡的对照，但未找到针对同格机壳内部子模型的封闭判断，因此不能承诺解决本问题。当前 2.1.1 的 Forge 1.20.1 配置要求 Create >=6.0.8、Entity Culling >=1.11.2；若仍使用下面的 6.0.6 样本，不能直接安装该版本。[版本要求](https://github.com/lap2ka/nowheel/blob/1896ddf2f6ee46bd5ab7aaff39e15ea834f6768d/versions/1.20.1-forge/src/main/resources/META-INF/mods.toml)、[依赖版本](https://github.com/lap2ka/nowheel/blob/1896ddf2f6ee46bd5ab7aaff39e15ea834f6768d/versions/1.20.1-forge/gradle.properties)。

## 实现后必须验证的场景

同一场景放置成组运行的轴/小齿轮，分别测试裸露、仅包壳、开口完全封住、玻璃/半砖/开孔材质、拆掉封闭块、连续旋转视角、跨区块与重进世界。核对活动实例数、提交顶点数、CPU/GPU 帧耗时及 1% low；隐藏必须减少实际提交，不能仅用“画面看不见”或普通实体计数当作收益。原型的封闭判断耗时和邻块变化时的重上传成本也要计入。

移动结构、快速拆壳恢复、Ponder、光影阴影和资源包变化另做正确性检查；当前未验证这些路径，不报告 FPS 提升百分比。

## 本地证据与产物清理

旧客户端样本路径：`E:/Downloads/MC/DeceasedCraft_Beta_DH_5.10.17/.minecraft/versions/DeceasedCraft_Beta_DH_5.10.17/`。只读取相关 JAR 与配置：Create 6.0.6、内嵌 Flywheel 1.0.4、Entity Culling 1.10.5、Colorwheel 1.1.1；其中已有 CreateBetterFPS 1.1.1 的 mixin 处理缓冲构建，并非机壳剔除实现。

旧日志 `.materials/c_latest.log:3053` 记录 2026-09-23 从 instancing 回退到 off；用户已确认现在是 instancing，因此旧回退仅作为历史线索。没有修改该客户端配置或启动其真实存档。

字节码核查时临时提取的 Flywheel JAR 为 **860,893 字节**，核查完成后已删除。远程源码仅保留在工具会话中；未创建测试客户端、存档或 LOD 缓存。保留此报告和现有共享样本，本轮新增磁盘产物仅为本文档。
