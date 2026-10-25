# CloudStorage

Paper **1.21.1** / Java **21** 的云仓插件，当前版本 `0.2.0-SNAPSHOT`。源码独立实现，上游仅用于研究兼容行为；参考 commit 见 [来源说明](docs/references.md)。

配套客户端源码：[Moeary/litematica-printer-cloud](https://github.com/Moeary/litematica-printer-cloud)，基于四改 v1.1.5 的 `1.1.5-cloud.1+1.21.1` 云仓适配版。完整取料测试与限制见 [0.2.0 验证记录](docs/testing-0.2.0.md)。

玩家通过命令或 54 格分页大箱子访问 UUID 私人仓，也可在权限允许时切换全服共享仓。相同完整元数据的物品按 long 数量聚合，界面每格展示一组而实际库存可远大于原版堆叠上限。

## 快速开始

在 PowerShell 7 中：

```powershell
Set-Location D:\Code\Java\Cloud-Storage
pixi install --locked
pixi run build
pixi run server-prepare
# 仅在本人接受 https://aka.ms/MinecraftEULA 后执行：
pixi run server-prepare -AcceptEula
pixi run server-start
pixi run server-status
```

本会话已获指挥官明确同意并为本地测试服写入 EULA。测试服绑定 **127.0.0.1:25565**，默认开启正版身份验证；使用 Minecraft Java 1.21.1 连接。生成文件在 `run/server/`，不进入 Git。

```powershell
# 后台服控制，不开放 RCON，无新增网络控制端口
pixi run server-console "op 你的游戏名"
pixi run server-console "cs status"
pixi run server-stop
# 如需在当前终端运行，先停止后台服，再执行
pixi run server
```

日志：`run/server/console.log`、`run/server/logs/latest.log`。后台启动后等待日志出现 `Done` 再连接；`server-status` 报告的是进程状态。

## 游戏内使用

| 命令 | 行为 |
|---|---|
| `/cs`、`/cs open` | 打开 54 格分页云仓 |
| `/cs search 平滑石` | 按中文、英文或物品 ID 筛选，支持部分名称 |
| `/cs deposit [数量]` | 存入主手物品，默认整组 |
| `/cs deposit all` | 逐组存入背包主区域/快捷栏，跳过禁用品及云潜影盒 |
| `/cs withdraw stone 64` | 取出普通石头；背包满则保留未取出的库存 |
| `/cs list [页]` | 显示数量和完整元数据对应的唯一 ID 前缀 |
| `/cs withdraw <ID前缀> [数量]` | 同名物品有不同附魔/名称/数据组件时精确取出 |
| `/cs mode personal` | 使用本人 UUID 私人仓 |
| `/cs mode shared` | 使用全服共享仓；需要独立权限 |
| `/cs shulker [auto或目录页或材料名...]` | 获取/更新 UUID 云潜影盒，绑定当前仓库；默认自动目录 |
| `/cs shulker off` | 移除本人云盒并释放背包槽位；不改变云仓存量，已取出的真实材料保留 |
| `/cs port input` | 绑定准星对准的自有木桶为输入接口 |
| `/cs port output` | 绑定为输出接口，手持物品为精确元数据过滤样本 |
| `/cs port unbind`、`/cs port list` | 解绑或查看本人接口 |
| `/cs status` | 查看版本、冻结和待核对转移数 |

GUI：左键/Shift 取一组，右键取一个；在背包 Shift 点击存入。鼠标须为空。点击下排指南针，在聊天输入中文/英文/ID 即可搜索，内容不公开；30 秒有效，输入“取消”返回。右键指南针清除筛选。页脚提供翻页与刷新；数字键交换、丢弃、双击聚集和拖拽不生效。

新入库仅接受普通可堆叠物品。装备、工具、潜影盒、玩家头颅，以及带自定义名称、附魔、容器数据或其他 ItemMeta 的物品均被拒绝；`deposit all` 跳过它们，保留在背包。命令、GUI、光标回填和世界输入接口使用同一规则。升级前已经存入的特殊物品仍可从普通云仓取出，不删除旧资产。

模式切换不会迁移或合并资产。共享仓是**全服一份**，不是每个维度一份。私人仓由服务器认证 UUID 决定，玩家改名不影响；切换 online-mode/不正确的代理身份转发会改变 UUID，不能直接混用。

## 世界输入/输出接口

1. 插件启用后，由本人放置一个木桶。旧木桶必须重新放置，不能把任意别人的容器据为己有。
2. 在 5 格内对准木桶执行绑定命令。输出端需要主手拿着样本；绑定不消耗样本。
3. 用自有漏斗/容器自动输送；私人接口不接受无所有权标记的相邻容器与漏斗矿车。共享接口的物理进出是公共的。

接口固定保存世界 UUID、坐标、所有者 UUID 与仓库标识；本人之后切换模式不会改绑。绑定期间禁止破坏/爆炸替换；先解绑再拆除。只有所有者和管理员能开私人接口窗口。

当前版本只处理已加载区块，所有者必须在线且仍有权限；不加载区块，不提供离线抽取。每轮轮流处理一个端口、最多一组；窗口打开时暂停该木桶。为缩小跨存储崩溃窗口，端口转移会保存世界，**大量接口有主线程开销**，此版应先在小规模服务器评估。

## 打印机使用

研究目标为 [BiliXWhite/litematica-printer](https://github.com/BiliXWhite/litematica-printer) 的 PLUGIN 快捷潜影盒模式，无须另外安装 AxShulkers。

1. 云仓中存入普通无自定义元数据的建筑方块。
2. `/cs shulker`，云潜影盒直接发入背包主区域（客户端槽位 9..35）。旧盒在进服或重新执行该命令时会自动迁入主背包，保留 UUID/仓库绑定；不会用后台定时器强行搬动玩家正在操作的手持物品。打印时请将盒子留在主背包三排。
3. 打印机设置 `useQuickShulker=true`、`shulkerSource=PLUGIN`。
4. 配套 `1.1.5-cloud.1+1.21.1` 打印机通过 `cloudstorage:demand` 通道请求目标材料，收到服务端目录后右键开盒，再正常取料。普通玩家无需 OP；材料选择不使用聊天命令，不触发命令防刷。服务端以真实鼠标/背包与 SQLite 库存为准，不采信客户端预测的物品。回塞同样入账。

不用打印时执行 `/cs shulker off`。该命令销毁自己的目录壳，关闭打印窗口时由 Paper 归还已经取出的真实鼠标物品；再次执行 `/cs shulker` 可恢复，不会自动重发占用背包。

当前本地测试服另安装正式服下载的 PaperAccurateBlockPlacement 1.2.0 与 ProtocolLib 5.4.0，以支持 Carpet V2 精准放置。客户端应设 `easyPlaceProtocol=true`、Litematica 的 `easyPlaceProtocolVersion=v2`；`placeInAir=false` 保留正常支撑检查。此协议插件不提供 Servux 玩家持物确认，四改的 `servuxHandConfirmation` 必须关闭。只安装 CloudStorage 的服务器没有精准放置协议支持。

`auto` 改为按需目录：没有请求时为空，收到请求后仅展示一种有库存的候选材料，保留 26 格回填空间，不再轮巡千种库存。满背包时，配套模组会把主背包中一组非目标的普通建筑材料存回云仓，再取目标材料；保护快捷栏、自定义物品和装备。没有安全回填候选时暂停取料。开盒后冻结该次目录，避免交易中途改变。旧打印机仍可 `/cs shulker smooth_stone smooth_stone_slab` 固定最多 26 种材料，或 `/cs shulker 2` 使用固定页；旧客户端不支持新的 auto 按需协议。旧配置 `catalog-rotate-ticks`、`preferred-materials` 不再生效。

已用完整 Fabric 模组组合连接真实 Paper 验证按需取料和满背包换料，并核对服务端账本守恒；完整复杂投影的自动建造仍需玩家验收。客户端源码与 AGPL 说明在相邻 `D:/Code/Java/litematica-printer-cloud`。

云潜影盒的组件是发现目录，不是可带走的真实库存。禁止转交、放置、丢弃、死亡掉落、制作与存入外部容器。检测到 AxShulkers 时拒绝发放和打开云潜影盒并清空在线玩家目录，防止两个插件同时处理。不要热加载其他开盒插件；**也不要移除/热卸载本插件后携带云潜影盒在原版服务器中使用**。完整安全边界见 [security.md](docs/security.md)。

## 权限与配置

| 权限 | 默认 | 用途 |
|---|---|---|
| `cloudstorage.use` | 所有玩家 | 私人仓存取 |
| `cloudstorage.printer` | 所有玩家 | 云潜影盒 |
| `cloudstorage.shared` | OP | 全服共享仓 |
| `cloudstorage.port` | OP | 世界接口 |
| `cloudstorage.admin` | OP | 恢复审计、管理接口 |

默认配置生成在 `plugins/CloudStorage/config.yml`。容量 10 亿件、4096 种、单次命令最多 2304 件；禁用物品、默认模式、接口周期与打印目录刷新周期均可配置。配置修改后使用正常 stop/start，禁止 `/reload`。

## 测试与恢复

```powershell
pixi run test          # 真 SQLite 单元/事务测试
pixi run build         # 测试后构建独立插件 JAR（包含 SQLite JDBC）
pixi run integration   # 真实 Paper + 双协议客户端，独立 127.0.0.1:25566
pixi run audit         # 只读完整性检查、仓库数量及待处理日志
pixi run audit --backup D:/Backups/cloud-storage-20261024.sqlite
```

集成服在 `run/integration/<时间>/`，使用独立离线身份，包含真实 JVM 强杀/重启测试，**不会连接或清理手动测试服**。依赖锁定在 `tests/package-lock.json`。具体覆盖和未验证项见 [测试记录](docs/testing.md)。

SQLite journal 与数量变化原子提交；玩家/世界文件与 SQLite 无分布式事务。非正常停止、未完成转移或数据库错误时冻结所有交易，不自动重放物品。管理员先联合备份数据库、玩家和世界，核对 `/cs recover list` 以及 [审计脚本](scripts/audit.py)，再明确处理：

```text
/cs recover <日志UUID> before   # 外部库存仍是转移前状态，回退云仓变化
/cs recover <日志UUID> after    # 外部库存已经转移，保留云仓变化
/cs recover unlock             # 全部待定项处理完且 DONE 记录也审计完成后解锁
```

恢复命令只调整账本，不自动发物品。不能将 `recover unlock` 当作每次启动必跑的命令。

正常停服也须等世界异步保存收尾：插件只登记 shutdown_ready，提供的启动脚本在实际 JVM 正常退出后才清除 dirty。若使用其他 Java 启动器/面板，必须在进程退出后调用 `scripts/checkpoint.py <数据库路径> <JVM退出码>`；否则下一次启动会保守冻结。不能在 JVM 仍运行时手工调用此脚本。

## 目录与版本管理

```text
Cloud-Storage/
├── AGENTS.md                 项目不变量与协作规则
├── pixi.toml / pixi.lock      Java / Maven / Node / Python 环境
├── pom.xml                   固定 Paper API 与构建工具版本
├── src/main/                 插件分层实现与配置
├── src/test/                 SQLite 测试
├── tests/                    真实服协议客户端及 npm 锁
├── scripts/                  测试服控制、集成测试、只读审计
├── docs/                     架构、安全、兼容来源、测试与版本说明
├── references/               上游只读克隆（忽略）
├── run/                      测试服、存档和证据（忽略）
└── target/                   构建产物（忽略）
```

Git 已初始化为 `main`，没有创建提交或远程推送。使用 SemVer 与 [CHANGELOG](CHANGELOG.md)，发布/迁移约定见 [版本管理](docs/versioning.md)。
