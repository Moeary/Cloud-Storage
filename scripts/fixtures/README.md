# 本地全物品测试夹具

`SeedSharedItems.java` 是一次性 Paper 辅助插件，不进入 CloudStorage 产品 JAR。它在实际 Paper 1.21.1 中枚举非 legacy、非空气的 `Material.isItem()`，使用服务端完整 ItemStack 序列化，并通过 Ledger 的 prepare/settle 为共享仓每种补足 100000 个；现有更高数量保留。

2026-10-24 已按指挥官要求实际填入 **1332 种 / 133200000 件**，所有 journal 均 DONE，无 PENDING。配置保持正版验证、127.0.0.1、生存、和平、超平坦，并临时为本地测试玩家开放共享仓和接口权限，云仓禁用物品列表清空以便测试全物品。

辅助 JAR 填充后已从 `run/server/plugins/` 移出，保留在 `run/fixtures/all-items-helper/`；后续重启不会自动补货。报告在 `run/server/plugins/CloudStorageFixture/report.json`，配置及 SQLite 备份在 `run/backups/`。数量不包含无限多种自定义 NBT/药水等元数据组合。

若明确需要再次重建此测试夹具，先正常停止服务器、确认没有 PENDING 并备份，生成依赖 classpath 后使用 `pixi run python scripts/fixtures/prepare_all_items.py` 准备并启动；看到 ALL_ITEMS_READY 后再次停止、移出辅助 JAR 再启动。禁止将此辅助插件放入正式服，禁止在 JVM 运行期间离线改写云仓数据库。
