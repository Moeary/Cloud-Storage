# 0.2.2 面板直接启动与停机保护验证

2026-10-25，Windows / pixi Java 21.0.10。插件 `0.2.2`，客户端模组和 v3 协议不变。

## 修复依据

旧测试启动器在 JVM 退出后总会执行 Python finalizer，掩盖了独立 JAR 无法自行识别正常关闭的问题。0.2.2 回归主动取消这一调用，按 MCSM 的直接 `java -jar paper.jar --nogui` + 控制台 `stop` 方式测试。

Paper 1.21.1 build 133 和正式服同款 build 116 的末期标志已通过实际服务器验证：玩家/世界保存与 RegionFileIOThread.deinit 完成后才设置 hasFullyShutdown，abnormalExit 分支请求退出 70。插件关闭钩子只读这些状态，写入原子退出凭据；不在 onDisable 清除 active。下一次启动须再核验旧进程身份已经退出、同 session 候选与无 PENDING。

## 实际结果

- `pixi run build`：27 项 Java 测试、5 项 Python 测试通过。新增独立子 JVM 退出凭据、生存进程拒绝、旧凭据/无候选拒绝、PENDING 不得放行测试。
- `pixi run integration-placement`：证据 `run/integration/20261025-100852/result.json`，262.6 秒；**supervisor_finalizer=false**。
- 主回归包含普通玩家共享仓、接口 5+5、中文搜索、特殊物品禁存、光标取料回填、余额守恒和精准放置 V2。
- 两次无外部脚本的正常重启：非冻结，库存/玩家偏好/11 个接口保留。
- 运行中实际强杀 JVM：重启冻结，审计解锁前不重放。
- **CloudStorage onDisable 已写候选，但阻塞在后续插件关闭、世界尚未 flush 时强杀**：重启仍冻结，旧退出凭据不能通过。
- 热卸载 CloudStorage 后再正常停服：重启仍冻结。
- 设置 Paper abnormalExit 后完成关闭：重启仍冻结。
- 更高 schema 初始化失败：仍保留云目录保护。

正式服同版本 Paper **1.21.1 build 116** 的独立证据为 `run/panel-restart-116-20261025-100947/result.json`：32.0 秒，连续三轮直接启动/正常停止均为实际 JVM exit=0、frozen=false、退出凭据成功，未运行外部 finalizer。该测试为隔离本机 Java 进程，不是声称已在用户正式 MCSM 实例部署。

Paper 116 官方 JAR SHA256：`6d7fc7a360a04e1996e1f4ee9e74ace69d37eccb7205e4b564531fb2147fe8eb`。
插件 JAR SHA256：`52e376c4a79f8820c824ead31ab262dcc453a17c5f6adf6067d1c582c3589192`。

复现连续重启：

```powershell
pixi run python scripts/panel_restart_smoke.py --paper .cache/paper-1.21.1-116.jar --build 116 --port 25567
```

## 旧版升级

已有冻结、缺失退出凭据的历史不能仅由 pending=0 证明安全，因此升级不自动解锁旧状态。先联合备份数据库/玩家/世界，核对旧停服保存记录与近期存取；确认后控制台 `cs recover unlock` 一次，再正常 stop/start。新凭据从 0.2.2 的完整正常退出开始生成，后续正常重启无需反复人工解锁。

不手改 active，不丢弃数据库，不添加开服自动 recover unlock；保持异常与未完成转移保护。测试 probe 仅在隔离集成服，生产 JAR 不包含测试插件。
