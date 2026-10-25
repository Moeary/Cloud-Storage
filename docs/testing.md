# 测试记录

当前 0.2.2 面板直启、正常退出凭据及晚期停机强杀保护见 [testing-0.2.2.md](testing-0.2.2.md)。

最新共享仓权限、配置迁移和普通玩家接口 5+5 验证见 [testing-0.2.1.md](testing-0.2.1.md)。中文搜索、禁存规则、非 OP 真实 Fabric 按需取料与满背包换料结果见 [testing-0.2.0.md](testing-0.2.0.md)。以下为 0.1.x 历史证据，旧版允许元数据入库的行为已在 0.2.0 改为拒收。

日期：2026-10-24（Asia/Singapore）。目标 Paper **1.21.1 build 133**，Java **21.0.10**，原生 Windows / pixi 锁定环境。

## 客户端依赖兼容修复与进世界验证

指挥官实际进服于 21:29:03 触发 `MasaGadget 4.0.395` 的 `fixCarpetAccurateProtocol.MixinWorldUtils` 注入失败。对比原 0.19.59 与新版 Litematica 0.19.61 字节码：`doEasyPlaceAction` 内缓存调用从 `WorldUtils.cacheEasyPlacePosition` 移至 `EasyPlaceUtils.cacheEasyPlacePosition`，旧注入点不存在。此前仅完成启动/渲染初始化，未覆盖这个延迟类加载路径，不能作为进服兼容验证。

已将目标实例 `E:\Minecraft\.minecraft\versions\Xplus 1.21.1` 的 Masa Gadget 升为 **4.0.403+36d7522-stable**，MagicLib 从 **0.8.784** 升为 **0.8.882+e341501-stable**（新版 Masa Gadget 要求 MagicLib >=0.8.838）。使用官方 1.21.1 Fabric 稳定版 JAR，按 GitHub 发布附件摘要校验。四改 1.1.5、Litematica 0.19.61 继续保留。本轮不修改配置。

备份在 `run/backups/dependencies-20261024-214205/`，镜像在 `E:\Minecraft\Backups\Xplus-1.21.1\dependencies-20261024-214205/`：

- `current-before-dependency-update/`：完整 mods（包括已禁用文件）、config、版本 JSON/JAR、options、服务器列表、诊断日志，共 1462 文件，含逐文件 SHA256SUMS.json。
- `original-printer-combination/`：用当前其他模组及此前保留的原打印机 7.2.1 / Litematica 0.19.59 和此前完整 config 重建的组合，共 1417 文件；不是声称此前整实例的时间点快照。升级 JAR 在 excluded-upgrade-jars 单独存档。
- 两盘副本逐文件校验一致；README.md 说明退出游戏后如何用改名留档方式恢复完整 mods/config，避免混装。installed-update.json 记录实际安装与测试结果路径。

`pixi run client-world --client "E:/Minecraft/.minecraft/versions/Xplus 1.21.1" --replacements "D:/Code/Java/Cloud-Storage/.cache/dependency-update/replacements.json"` 已通过，证据 `run/client-world-20261024-214652/result.json`，75.2 秒。

测试使用与实际客户端一致的 Java 21 大版本（pixi JDK 21.0.10），完整目标模组、配置、资源包和光影包复制到隔离目录；独立 Paper 监听 **127.0.0.1:25566**，不接触手动服 25565。使用无账号凭据的 CloudSmoke 身份进入真实测试世界并持续运行 **30.1 秒**。测试专用 Fabric probe 在渲染线程确认 world/player 非空，主动初始化 `WorldUtils` 和 `EasyPlaceUtils`，并验证两类经过全部实际 Mixin 转换后成功导出，避免只进主菜单或未触发关键类造成假通过。测试 probe 只存在于隔离目录，没有安装进实际实例。登录观察的首次尝试未完成全部类加载断言，未计为通过；加入明确类加载 probe 后完成上述验收。

安装后实际实例 **89 个启用 JAR** 的完整文件名/哈希集合与通过测试的模组集合一致（排除测试 probe）。原两个依赖另存 `run/retired-dependencies/20261024-214821/`；手动测试服持续正常后台运行。未进行完整投影自动建造验收，不将进世界测试描述成打印性能/正确性全面验收。

## 0.1.2 关闭云盒与精准放置 — 本次验证

- `pixi run build`：18 项 Java、5 项 Python 测试通过。
- `pixi run integration-placement`：真实 Paper + ProtocolLib 5.4.0 + PaperAccurateBlockPlacement 1.2.0，完整四阶段通过，证据 `run/integration/20261024-211231/result.json`，94.8 秒。
- 新增关闭云盒场景：打印 GUI 中持有已扣账的 64 石头时关闭，材料归还背包；目录壳从普通槽/鼠标移除；重复关闭为 0、重新领取成功；选中共享仓后撤销共享权限仍能关闭。
- V2 测试直接发送超出原版点击范围的编码 X：朝东漏斗成功放置且只消耗一个；连续 5 次无支撑告示牌放置均未消耗；有支撑的告示牌只消耗一个。物品先经云仓入库/取出，核对玩家、世界和账本守恒。
- 原服务器日志中有 1142 条 `Rejecting UseItemOnPacket`，点击 X 与目标方块相差多个方块，与启用 V2 但服务端无协议插件一致。旧日志不记录每次物品扣减，不能凭拒包日志认定或补偿“吞物品”。
- 协议 JAR 和 config 从指挥官指定的正式服文件管理界面只读下载；未修改、停服或部署到正式服。
- 本地受控停服后的联合备份：`run/backups/pre-placement-20261024-211858/`，含三个维度、玩家、插件和 SQLite。停服审计 `integrity=ok / active=false / pending=[]`，共享仓 1332 种 / 133199445 件，保留实际测试消耗，不重填货。

客户端目标按指挥官最后指令为 `E:\Minecraft\.minecraft\versions\Xplus 1.21.1`。旧实例 `XPlus PerioTable based on Minecraft 1.21.1 (Fabric)` 的临时配置改动已从 `.cache/client-backups/20261024-210518/litematica-printer.json` 原样恢复，SHA-256 一致，旧实例的模组未替换。本地三改源码的未部署退避草稿另存 `run/drafts/printer-retry-not-deployed/`，源码工作区恢复干净。

四改官方 1.1.5（2026-10-24 发布）确有 1.21.1 JAR，commit `80b2d225f56038e0d9a40f07c151fc965ab8b108`，SHA-256 `9458d9b327a3dae2176bca12c973c339292921dbb53315dfa68cb16a90d8a1ce`，与作者 SHA256SUMS 一致。PLUGIN 路径仍用两次 PICKUP，未复制其 AGPL 源码到本插件。作者发布验证和本机独立验证须区分，完整自动投影打印仍需玩家客户端验收。

目标新实例原有 MaLiLib 0.21.10、Item Scroller 0.24.60、MiniHUD 0.32.60、Tweakeroo 0.21.61、Fabric Loader 0.18.4 / Fabric API 0.116.8，均保留。仅将 Litematica 0.19.59 升为 0.19.61，并将 `litematica_printer` 7.2.1 替换成四改 `litematica-printer` 1.1.5+1.21.1；两个打印机的 Mixin 配置同名，不能并存。原两个 JAR 和完整 config 备份到 `run/backups/client-Xplus-1.21.1-20261024-212537/`，清单 `manifest.json` 含前后 SHA-256。

新建打印机配置：Alt+X 总开关（初始关闭）、Alt+A 打印模块（初始开启）、X+Y 设置，PLUGIN 云盒路径、V2 精准放置、关闭 Servux 手持确认、关闭空中放置、2 tick 间隔/10 tick 冷却/每次 1 方块。仅将现有 Litematica 配置的协议选择由 auto 改为 v2。

本机隔离 Fabric 启动证据 `run/client-smoke-20261024-212308/result.json`：采用目标实例全部模组，替换上述两个 JAR，完成资源加载/渲染初始化并保持运行，36.2 秒后结束自建测试子进程。未打开世界、未连服、未使用账号凭据。测试运行时是单独 pixi Java 23 环境（整合包 C2ME 要求至少 Java 22），未改变服务端 pixi Java 21 或启动器配置。隔离目录未复制光影包，因此有 EuphoriaPatcher 缺光影、部分第三方 ModMenu API 与翻译警告；不能宣称全模组无警告或完成投影建造。写入最终配置后另行执行相同启动检查。

最终配置的第二次隔离启动通过：`run/client-smoke-20261024-212539/result.json`，37.2 秒，完整目标模组组合与写入的 V2/PLUGIN 配置完成渲染初始化。手动服 21:25 控制台确认 `version=0.1.2-SNAPSHOT frozen=false pending=0`。部署、构建与本轮集成测试所加载的 CloudStorage JAR SHA-256 均为 `d190cf042ec13aca4a63e12a76e96d41f6c38ff0c7bd2814f79c0f883eb6e75d`。

## 0.1.1 打印机兼容修复 — 历史记录

实际安装客户端为三改打印机 `1.3-beta.4-1790562282161-local`，已读取其 JAR 字节码核实：跳过快捷栏、普通 PICKUP 两次转移、取料后立即关窗。初版按照当前上游的 QUICK_MOVE 路径验证，未覆盖这个实际版本，造成兼容遗漏。

- **18 项 Java 测试通过**（原 14 项账本测试 + 4 项全目录轮巡/常用材料固定/精确过滤/边界测试）。
- **5 项 Python checkpoint 测试通过**。
- **19 项真实 Paper 协议场景全部通过**，证据 `run/integration/20261024-204410/result.json`，四阶段共 **79.8 秒**。新增了旧版光标取料/回塞、相同 stateId 的两次连续预测点击后立即关窗、光滑石/半砖过滤和快捷栏盒子的重发迁移；其余原有资产隔离、GUI 防护、端口、正常重启、强杀恢复和初始化保护继续通过。
- 最终部署 JAR 为 `0.1.1-SNAPSHOT`，SHA-256 `196e5db35b716d1f430c62393640fbd74d19e51621f58931cc8b69ec1575becc`；与完整集成测试加载的产物一致。
- 手动服已恢复后台运行于 `127.0.0.1:25565`，状态 `frozen=false / pending=0`；共享仓保留 **1332 种 / 133200000 件**，没有重置世界或重新补货。
- 停服期间的世界/维度/玩家与 SQLite 联合备份在 `run/backups/printer-compat-20261024-2041/`。客户端配置和模组未修改。

仍需指挥官在完整 Fabric 客户端进行自动建造复测；协议路径通过不能代替渲染/模组调度的完整实机验收。重新登录后旧盒会移入主背包，`/cs shulker auto` 更新目录；必要时 `/cs shulker smooth_stone smooth_stone_slab` 固定目标材料。总工作开关为左 Alt+X，Alt+A 只切换打印模块。

## 0.1.0 首版历史记录

- **14 项 Java / 真实 SQLite 测试**：UUID/共享仓隔离、事务回滚、容量/种类上限、完整物品身份、无效数量/溢出、重复操作 ID、PENDING 重启恢复、before/after 人工恢复、clean/dirty 区分、并发扣账不透支、1000 步随机数量守恒、偏好/端口持久化、更高 schema 拒写，以及卸载阶段 shutdown_ready 候选不能提前清除 dirty。
- **5 项 Python checkpoint 测试**：实际 JVM 正常退出且 session 候选一致才清除 dirty；强杀、候选过期、PENDING 存在和新 schema 都保持冻结状态。
- **15 项真实 Paper 协议场景**：见下表。独立进程、真实 TCP 数据包、双玩家身份和实际 SQLite 账本，不是 MockBukkit 库存模拟。

| 场景 | 结果 |
|---|---|
| 主手/整背包命令存取与数量核对 | 通过 |
| 不同 UUID 无法取对方库存，无权限不能访问共享仓 | 通过 |
| 54 格 GUI Shift 出入、按钮不能拿走、数字键/丢弃被拒绝 | 通过 |
| 满背包取货为 0，库存不扣减 | 通过 |
| 负数与超大数量输入 | 通过 |
| 双客户端同时取共享仓一组，仅能总计交付一组 | 通过 |
| 共享仓权限被撤销仍可切回 personal | 通过 |
| 客户端可见 container 目录组件、右键开盒、QUICK_MOVE 取料/回塞，窗口数量差确认 | 通过 |
| 云潜影盒丢弃/放置被阻止，其他 UUID 复制到壳也不能打开 | 通过 |
| 自定义名称物品单独存储，Material 取货出现歧义时拒绝 | 通过 |
| 玩家真实放置木桶，输入/输出守恒，非所有者漏斗不能抽私人接口 | 通过 |
| 完成操作没有遗留 PENDING | 通过 |
| 正常 JVM 退出后 finalizer 清除 dirty，重启库存/模式保留且能继续存取 | 通过 |
| 强杀实际 JVM，重启拒绝交易，人工审计解锁前不重放物品 | 通过 |
| 更高 schema 导致初始化拒写时，云潜影盒事件保护仍然保留 | 通过 |

首版完整通过：`run/integration/20261024-195536/result.json`，四阶段 `main / clean-restart / forced-stop-recovery / initialization-guard`，用时 **74.5 秒**。该目录的日志、世界、玩家数据、数据库与结果保留作证据；最后一阶段故意把测试 schema 设为 99，其初始化 ERROR 是预期断言，不是漏掉的失败。

手动测试服另行完成后台启动 → 控制台状态命令 → 正常停止 → 只读审计 `active=false / quick_check=ok` → 重启。SQLite 在线备份 API 也已实际执行，备份在 `run/backups/`。测试服保持正版身份验证并只监听本机。

首版 JAR SHA-256：`6419a610490c3fa65d63c3f336b1a844486ba5b4735ce3ddae0ab9ce7d1e22d1`。连续两次 package 得到相同摘要，ZIP 无重复条目；首版包的 26 个插件 class/资源条目与上述历史集成测试所加载的内容逐字节相同（其中包含目录条目）。Maven Jar 使用 forceCreation，避免增量打包时再次以已 shade 的产物作为输入。

## 范围与限制

- **未完成完整投影自动建造验收。** 已运行完整 Fabric 客户端登录隔离 Paper 世界并验证原崩溃类加载；库存协议已验证发现组件、右键、取料/回塞与确认包路径。这些结果不能代替投影建造正确性和性能验收。
- 测试客户端的 minecraft-data 1.21.1 配方解析对 Paper 盔甲纹饰配方报 PartialReadError；测试适配器将仅 `declare_recipes` 保留为 opaque bytes，未跳过任何库存、物品组件、点击或方块包。不覆盖合成配方同步。
- 没有穷举双击、拖拽、创造模式、死亡、所有第三方插件组合和断电中的每个指令窗口；产品事件路径已防护，仍需生产前人工回归。AxShulkers 并存会阻止发放/打开云潜影盒，不承诺任意开盒插件兼容。
- 没有大型服务器吞吐/TPS 基准、海量物品 NBT 压力、长期磁盘故障或真实物理断电测试。世界接口同步触发保存，初版适用于小规模验证。
- 无跨服务器 SQLite 共用、Folia 或离线所有者接口支持。

## 复现

```powershell
Set-Location D:\Code\Java\Cloud-Storage
pixi install --locked
pixi run build
pixi run integration
```

完整测试需要先明确接受 Minecraft EULA 并准备固定 Paper JAR。集成脚本仅启动自己的 `127.0.0.1:25566` 进程，生成新的时间戳目录，不重置或清理 `run/server`。测试中 JVM 强杀通过直接的 `JAVA_HOME/bin/java.exe` 子进程句柄进行，避免只结束 pixi 的 Java shim 而遗留真正的服务器。
