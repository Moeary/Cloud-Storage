# Cloud Storage — 项目协作约定

## 目标与环境
- 仅支持 Minecraft **Paper 1.21.1 / Java 21**，原生 Windows + PowerShell 7。
- 所有构建和测试通过 `pixi run`；环境由 `pixi.toml` 与 `pixi.lock` 管理。
- 修改前阅读本文、`docs/architecture.md`、`docs/security.md`，检查 `git status --short --branch`。
- 使用 `rg` / `rg --files`；保留用户无关改动。没有明确指令不提交、推送或部署到外部服务器。

## 目录职责
- `src/main/java/dev/cloudstorage/`：`storage` 数据库与账本，`transfer` 游戏库存事务，`ui` 菜单，`port` 世界接口，`printer` 云潜影盒，`command` 命令。
- `src/main/resources/`：插件声明和默认配置。
- `src/test/`：真实 SQLite 事务测试；`tests/`：真实 Paper 协议测试客户端。
- `scripts/`：环境、构建、测试服准备与启动，禁止把测试工具放入产品代码。
- `docs/`：架构、安全边界、兼容性、测试证据与版本记录。
- `references/`：用户指定上游的只读克隆，忽略 Git，不作为运行依赖。
- `.cache/`、`.pixi/`、`target/`、`run/`：本地生成文件，禁止进入版本控制。

## 编码与不变量
- Java 21，按职责拆类；主线程调用 Bukkit API。云仓按玩家 UUID 或固定 `shared` 标识隔离，切换不会自动搬迁资产。
- 物品键保存完整服务端 ItemStack 数据（数量归一化），不能仅按 Material 合并；数量使用 long，严格检查溢出、容量与正数。
- 所有出入库共用事务服务；禁止事件监听器直接改数据库数量。
- 菜单物品是展示副本，必须取消默认点击和拖拽；不信任客户端 NBT、标题、数量或槽位。
- 云潜影盒的内容仅为打印机发现索引，不是真实物品；必须保护放置、丢弃、死亡、制作、容器及其他插件开盒路径。
- 世界接口须校验所有者、位置、世界 UUID、区块加载、容器类型及权限；禁止跨玩家改绑。
- SQLite WAL + FULL，数据库扣账/入账与 journal 原子提交。游戏库存与数据库不具有分布式原子性，未完成转移和非受控停机必须 fail closed，禁止自动重放待定物品。
- 数据库故障禁止继续交易；禁止把“未运行”“协议模拟”写成真实客户端打印验证。
- Bukkit 卸载阶段不得清除 active/dirty；只能写 shutdown_ready。实际 JVM 正常退出后，由 scripts/checkpoint.py 校验 session 和 PENDING 再清除，禁止在进程运行时调用 finalizer。

## 检查与交付
- `pixi run test` / `pixi run build`；修改后运行相称的集成测试 `pixi run integration`。
- 测试服仅监听 `127.0.0.1`。离线身份模式只能用于此隔离测试服；生产必须 online-mode 并保护代理身份转发。
- 不自动接受 EULA；本会话指挥官已明确同意本地测试服 EULA，可写入本地 `run/server/eula.txt`。
- 发布使用 SemVer；在 CHANGELOG 记录变更、迁移与已知限制。交付说明实际测试范围和风险。

## 参考方式
- 已阅读 AxShulkers 的开盒/库存事件、ExtraStorage 的 Storage 抽象，以及相邻 remote-inventory 项目的 AGENTS 目录说明方式。
- litematica-printer 是 AGPL-3.0，仅研究可观察协议与兼容行为；不复制其实现。其他参考也不直接复制源码，来源与固定 commit 记入 `docs/references.md`。
