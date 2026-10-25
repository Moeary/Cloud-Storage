# 版本管理

- 工作分支：`main`。仓库公开托管于 `https://github.com/Moeary/Cloud-Storage`；2026-10-25 经用户明确要求首次提交并推送源码。运行世界、数据库、备份、缓存和参考克隆不进入 Git。
- 当前代码版本：Maven / plugin 与 pixi 工作区 `0.2.3`。
- 配套客户端独立托管于 `https://github.com/Moeary/litematica-printer-cloud`，分支 `cloud/1.21.1`，版本 `1.1.5-cloud.2+1.21.1`；保留 water2004 v1.1.5 的上游历史与 AGPL-3.0 许可证。
- 遵循 SemVer：补丁修复不改变 schema；新增兼容能力升 minor；不兼容 schema/API 升 major。更新 `pom.xml`、`pixi.toml`、CHANGELOG 与 README 版本描述。
- schema 版本为 `PRAGMA user_version=1`。更高版本数据库直接拒绝写入并保留云潜影盒保护，不自动降级。
- 环境锁在 `pixi.lock`，协议客户端依赖锁在 `tests/package-lock.json`；Paper server build 133 的 SHA-256 固定，API 也使用时间戳版本，避免 SNAPSHOT 漂移。
- 构建：`pixi install --locked` 后 `pixi run build`，产物 `target/cloud-storage-0.2.3.jar`；不打包客户端模组。面板启停适配只读 Paper 1.21.1 的公开关闭标志，缺失时保守回退，不反射修改服务端库存。
- 发布前必须通过单元与真实服集成测试，更新 `docs/testing.md` 和产物 SHA-256；尚未完成 Fabric 客户端验收时不得标注正式稳定版。
- 更新测试服：先 `pixi run server-stop`，完成联合备份后构建，再 `pixi run server-start`。后台启动脚本只复制 JAR，不能更新运行中的 JVM。
- 回退：正常停止服务器，再联合恢复同一时间点的数据库/玩家/世界。禁止只回退 SQLite 或单独 playerdata；禁止直接复制运行中 SQLite 主文件而遗漏 WAL。审计脚本 backup 使用 SQLite 备份 API。

后续提交保持变更与验证证据一致；提交、推送与 Release 按指挥官已明确提出的仓库发布要求执行。本地世界、数据库、玩家数据、账号文件不上传。
