# 测试产物占用清理（2026-10-05）

上一次去重仅修改测试代码，没有删除历史运行复制的游戏目录。本次按文件长度统计，项目由 166.420 GiB（178.692 GB）降至 12.074 GiB（12.964 GB），释放 154.346 GiB（165.728 GB）。GB 为十进制，GiB 为二进制。

| 删除内容 | 数量 | 大小 |
| --- | ---: | ---: |
| live 各轮客户端 / 服务端游戏副本 | 35 个目录 | 92.145 GiB |
| 实体 CPU 压测游戏副本 | 18 个目录 | 39.537 GiB |
| 专用服 smoke 生成的临时 world | 47 个目录 | 1.523 GiB |
| 大型 profiling 展开 JSON | 20 个文件 | 21.141 GiB |

保留唯一可复用源环境 `build/benchmarks/lod-square256-cache128/server/` 和 `client/`，服务器预生成地形和 LOD 数据约 4.839 GiB。保留构建依赖、源码、测试代码、发布产物、日志、CSV、截图、结果摘要，以及 45 份原始 JFR；半径 256 的 7 份 `measurement.json` 全部保留。不同代码和调参运行的测量记录仍是各自独立的证据，只有复制的游戏数据和大型展开文件被删除。

删除前确认没有 Java / Python 测试进程；所有目标均位于本项目 `build/`，逐项检查绝对路径、祖先与递归范围无目录链接后用 PowerShell 删除。删除后确认 120 个目标全部不存在，唯一源环境大小未变，最新发行 JAR 仍可通过 SHA-256 核验，Git 原有内容未改。

后续运行继续从唯一源环境生成临时副本。`lod-live-benchmark.py` 成功退出后默认删除游戏副本；只有准备缓存重开输入时才临时使用 `--retain-run-games`，用完后清理，失败运行和实体压测副本也不应长期积累。本次未修改生产代码或测试脚本。

逐项删除清单和前后统计保存在 ignored `build/cleanup-test-artifacts-plan.json`、`cleanup-test-artifacts-result.json`、`cleanup-size-before.json` 与 `cleanup-size-after.json`。
