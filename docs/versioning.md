# 版本管理

- 工作分支：`main`。仓库公开托管于 `https://github.com/Moeary/Cloud-Storage`；2026-10-25 经用户明确要求首次提交并推送源码。运行世界、数据库、备份、缓存和参考克隆不进入 Git。
- 当前代码版本：Maven / plugin `0.2.0-SNAPSHOT`，pixi 工作区 `0.2.0`（包管理器不使用 Maven snapshot 后缀）。
- 配套客户端独立托管于 `https://github.com/Moeary/litematica-printer-cloud`，分支 `cloud/1.21.1`，版本 `1.1.5-cloud.1+1.21.1`；保留 water2004 v1.1.5 的上游历史与 AGPL-3.0 许可证。
- 遵循 SemVer：补丁修复不改变 schema；新增兼容能力升 minor；不兼容 schema/API 升 major。更新 `pom.xml`、`pixi.toml`、CHANGELOG 与 README 版本描述。
- schema 版本为 `PRAGMA user_version=1`。更高版本数据库直接拒绝写入并保留云潜影盒保护，不自动降级。
- 环境锁在 `pixi.lock`，协议客户端依赖锁在 `tests/package-lock.json`；Paper server build 133 的 SHA-256 固定，API 也使用时间戳版本，避免 SNAPSHOT 漂移。
- 构建：`pixi install --locked` 后 `pixi run build`，产物 `target/cloud-storage-0.2.0-SNAPSHOT.jar`；无 NMS 反射或客户端模组打包。
- 发布前必须通过单元与真实服集成测试，更新 `docs/testing.md` 和产物 SHA-256；尚未完成 Fabric 客户端验收时不得标注正式稳定版。
- 更新测试服：先 `pixi run server-stop`，完成联合备份后构建，再 `pixi run server-start`。后台启动脚本只复制 JAR，不能更新运行中的 JVM。
- 回退：正常停止服务器，再联合恢复同一时间点的数据库/玩家/世界。禁止只回退 SQLite 或单独 playerdata；禁止直接复制运行中 SQLite 主文件而遗漏 WAL。审计脚本 backup 使用 SQLite 备份 API。

后续提交保持变更与验证证据一致；提交与推送需由指挥官明确要求。未自动创建 GitHub Release 或上传本地游戏数据。
