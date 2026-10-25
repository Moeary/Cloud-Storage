# 0.2.3 云盒 / AxShulkers 共存验证

2026-10-25；Windows / pixi Java 21（Paper），完整 Fabric 模组组由独立 pixi Java 23 启动。版本：CloudStorage `0.2.3-SNAPSHOT`、打印机 `1.1.5-cloud.2+1.21.1`、官方 AxShulkers `1.24.0`、Paper 1.21.1 build 133。

## 行为与边界

- 云盒及云仓窗口只经过 CloudStorage 逻辑；Ax 继续负责普通潜影盒的右键开盒、实际内容、UUID 与持久化。无需改 Ax 的配置、JAR 或数据库。
- 隔离位于 Bukkit RegisteredListener 委托边界。原因是 Ax 的开盒回调不跳过已取消点击，且 blacklisted-items 只控制往盒中存放，不能充当开盒排除规则。
- 只放行已经核对的 1.24.0 主类与监听器布局；未知版本保持云盒保护。`/cs status` 的 `shulker-isolation=ready` 表示隔离就绪，`blocked` 时不能发放/打开云盒。
- 打印机使用本人 v3 云盒优先，主背包里放在更前面的实体盒不能抢先。auto 云仓缺货的初始等待约 20 tick 后允许查询实体盒；旧/他人云目录不会当成实体物品来源。
- 满背包回填仍只选择主背包中普通非目标方块，不碰快捷栏、自定义组件、工具或其他盒子。未新增普通盒存取实现，不替代 Ax 的安全策略。

## 真实 Paper 协议客户端

`run/shulker-coexist-20261025-135954/result.json`：76.4 秒，三阶段通过。

1. 非 OP 玩家交替开云盒与普通蓝色盒，云盒三轮两次 PICKUP 取料及回填，余额始终回到 128；实体盒 32 石头单独取出、回存，云仓账本不变。
2. 正常停服并直接重启，再重复上述流程，库存/普通盒内容不变，云仓未冻结。测试没有外部 finalizer。
3. 仅在隔离副本中把 Ax plugin.yml 版本号改为未支持版本，再重启：云盒发放/打开被拒绝，普通盒仍可使用，余额不变。

同时验证云盒空中手持右键、拒绝丢弃、在云窗口中运行 `/cs shulker off`、他人 UUID 无法开盒、无 PENDING。正常阶段 Ax SQLite `axshulkers_data` 始终仅一条实体盒记录，云目录未产生 Ax UUID/数据库记录。

复现（先从官方发布页取得固定版本 JAR）：

```powershell
pixi run integration-shulkers --axshulkers .cache/axshulkers/AxShulkers-1.24.0.jar
```

测试脚本核验官方 JAR SHA256 `ce53478f169d2099e827aa5c4dda4a75c280a4ee12664fce46479587521f6dfb`。未支持版本的元数据修改只发生在临时测试服副本，原官方文件保持原样。

## 完整 Fabric 客户端

`run/client-world-20261025-140020/result.json`：86.7 秒，通过。复制实际 `E:/Minecraft/.minecraft/versions/Xplus 1.21.1` 模组组/配置，换入候选打印机，使用无启动器凭据的隔离客户端和本地隔离 Paper，非 OP 身份。

- 实体盒放 inventory.0，含石头 32、砂砾 16；云盒在后面的 inventory.1，云仓有石头/红色混凝土各 64。
- 首次按需石头补料 411 ms，玩家取得 64 石头，实体盒的 32 石头不动，证明优先来源为云仓。
- 云仓没有砂砾时，1256 ms 后由 Ax 实体盒取出 16；等待实体盒的服务端关闭确认后，核验其石头仍为 32、砂砾为 0。Ax 开盒期间会暂时清空携带盒的内容组件，测试不把这段暂态当成丢物。
- 清理隔离测试材料、填满背包后，343 ms 内回存一组圆石并取出红色混凝土。快捷栏 9 组木板不动，光标为空。
- 服务器最终云仓为石头 64、圆石 64，PENDING=0；WorldUtils/EasyPlaceUtils 实际 Mixin 转换成功，进入世界后持续运行 30.1 秒。

这不是完整大型投影的建造速度测试，也不是在正式 MCSM 实例部署验证。现有正式服务器、手动测试世界与实际客户端文件均未修改。

## 构建与原功能回归

`pixi run build`：27 项 Java、5 项 Python 测试通过；打印机生产 JAR 与独立 cloud-test JAR 经 Gradle/remap 编译成功。生产 JAR 不包含 CloudRestockProbe。

`pixi run integration-placement`（`CLOUD_TEST_PORT=25569`）通过，证据 `run/integration/20261025-140020/result.json`，273.9 秒。包含中文搜索、特殊物品禁存、世界接口 5+5、精准放置 V2、两次无外部 finalizer 的正常重启、运行中强杀、卸载后保存尚未完成即强杀、热卸载、Paper abnormalExit=70 与 schema 初始化失败保护。此协议客户端回归没有运行 Fabric；完整 Fabric 证据为上文单独的 client-world 结果，不混淆两类验证。

候选生产 JAR SHA256：插件 `69453e15e516222d3fd9d315702d485e31a6f9327a00101de4609d262f93bb9f`；打印机 `aed780ea7582d6d545e870e9c78faa80b2c4c04b81deee23005e946fc32b35bd`。候选本地包保留在 `run/releases/0.2.3-SNAPSHOT/`。

## 发布构建

插件版本定为 `0.2.3`，JAR SHA256 `05560b823218d3c848b3b0752980a10d53658ba3fd9747769c409ad86c35d769`。与上述已验证候选逐 ZIP 成员比较：仅 plugin.yml、Maven pom.xml/pom.properties 的版本元数据变化；全部类文件、配置和其他资源完全相同。再次执行 `pixi run build`，27 项 Java、5 项 Python 通过。打印机发布 JAR 与上述完整 Fabric 测试的二进制逐字节一致，SHA256 不变。

最终发布 JAR 再次运行官方 Ax 1.24.0 三阶段共存回归：`run/shulker-coexist-20261025-183746/result.json`，72.7 秒通过，包括普通/云盒交易守恒、直接正常重启、未知版本只阻止云盒；Ax 数据库仍只有一条实体盒记录。

GitHub 发布标签为插件 `v0.2.3`、打印机 `v1.1.5-cloud.2`，均为预发布；各自附生产 JAR、对应提交的完整源码 ZIP 与 SHA256SUMS.txt。未部署正式服或修改实际客户端文件。
