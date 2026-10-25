# 0.2.0 验证与本地更新记录

2026-10-25，Windows / pixi Java 21.0.10，Paper 1.21.1 build 133。

- `pixi run build`：19 个 Java 测试、5 个 Python 测试通过；客户端 Gradle 1.21.1 生产包和独立 cloud-test 包构建通过。
- `pixi run integration-placement`：证据 `run/integration/20261025-085504/result.json`，132.7 秒，四阶段通过：主测试、正常重启、强杀后冻结/审计解锁、schema 初始化失败时保留云盒保护。
- 主测试增加搜索按钮私聊输入、中文部分名称/官方名称/简称、中文取货、右键清除；命令和 GUI 拒收带名称/NBT 的石头、装备、潜影盒、玩家头颅并保留原物，木桶输入保留被拒绝的装备。
- 原有 UUID 隔离、共享仓竞态、满背包不扣货、旧版连续 PICKUP、光标回填、云盒关闭和精准放置 V2 漏斗/告示牌守恒继续通过。

真实 Fabric 验证：`run/client-world-20261025-085819/result.json`。完整 89 个实际模组的复制实例，以非 OP 玩家连接独立 Paper 25566。测试驱动调用生产取料路径，并断言初次请求在 `InventoryUtils.switchToItemsResult` 返回 WAITING，保留打印任务。

| 实际场景 | 结果 |
|---|---|
| 空目录按需请求 64 个石头 | 351 毫秒完成 |
| 主背包及快捷栏全满，没有旧的圆石取料记录，换取 64 个红色混凝土 | 343 毫秒完成 |
| 回填与库存守恒 | 云仓新增圆石 64，红色混凝土减少 64，石头余额 64；没有 PENDING |
| 玩家背包 | 目标材料 64，主背包圆石恰好减少 64，快捷栏 9 组木板未变，光标为空 |
| 原崩溃回归 | 实际 Mixin 转换后的 WorldUtils / EasyPlaceUtils 加载通过，进世界持续 30 秒 |

这些时间是本机孤立取料流程测量，不代表大型投影的整体速度。尚未自动完成整张复杂投影；用户仍需用自己的投影验收告示牌、漏斗等实际建筑行为。

更新前快照：

- `run/backups/pre-cloud-demand-20261025-0851/client`：mods（包含 disabled 文件）、config、实例 JSON/JAR、options、服务器列表和诊断日志共 1463 文件；与 `E:/Minecraft/Backups/Xplus-1.21.1/pre-cloud-demand-20261025-0851` 双盘逐文件 SHA256 一致。
- 同级 `server/`：正常停机并完成 checkpoint 后的世界三维度、plugins/数据库、配置和日志，共 83 文件；SQLite `quick_check=ok`，共享库存 1332 种、133197186 件，PENDING=0。
- `installed-update.json` 记录最终安装摘要；只替换目标 `Xplus 1.21.1` 中的打印机 JAR，不改配置和其他依赖。旧 PerioTable 实例不修改。

插件版本 `0.2.0-SNAPSHOT`，SHA256 `ca4c780b3788ea04f6e8a1967ea6a07782458cf52f7f3155e188a2872951b67d`。
打印机版本 `1.1.5-cloud.1+1.21.1`，SHA256 `2d9fe8b815afb6dd2e846f608f54e2cb56b5b90c4d3f9fb45d24ded0ae69bf27`。
二进制及完整 AGPL 客户端对应源码位于 `run/releases/0.2.0/`。

复现客户端测试：

```powershell
pixi run client-world --client 'E:/Minecraft/.minecraft/versions/Xplus 1.21.1' --replacements '.cache/cloud-printer-candidate/manifest.json' --restock-probe 'D:/Code/Java/litematica-printer-cloud/versions/1.21.1/build/libs/litematica-printer-1.1.5-cloud.1+1.21.1-cloud-test.jar'
```

恢复客户端前先退出游戏，将当前 mods/config 改名留档，再复制完整旧快照；禁止混合两套打印机 JAR。恢复服务器必须先正常停止，再联合恢复世界/playerdata 与数据库，不能只用旧库覆盖新世界。
