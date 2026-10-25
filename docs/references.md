# 参考源码与固定版本

本项目是独立实现；没有复制下列项目源码，没有将参考仓作为编译或运行依赖。

| 项目 | 研究时 commit | 许可证 | 参考内容 |
|---|---|---|---|
| [BiliXWhite/litematica-printer](https://github.com/BiliXWhite/litematica-printer) | `217f9515ab06afd3de3bdd7392c6f4440e8c8e92` | AGPL-3.0 | PLUGIN 模式从 9 起扫描玩家背包，读取潜影盒容器组件，模拟右键开盒，QUICK_MOVE 取料并等待数量差确认 |
| [Artillex-Studios/AxShulkers](https://github.com/Artillex-Studios/AxShulkers) | `d9e508340d45cad3af336ba89b9f3ae6d10fda51` | MIT | 开盒事件、物品引用与库存事件的冲突面；安全检查不能只依赖关闭窗口保存 |
| [HSGamer/ExtraStorage](https://github.com/HSGamer/ExtraStorage) | `3b7137cd231a43c53a063580db19a914501585d9` | MIT | 长整数存量、容量/种类概念和 GUI 分离方式 |
| [water2004/litematica-printer 1.1.5](https://github.com/water2004/litematica-printer/releases/tag/v1.1.5) | `80b2d225f56038e0d9a40f07c151fc965ab8b108` | AGPL-3.0 | 1.21.1 发布与依赖要求、PLUGIN 的两次 PICKUP 取料/回塞边界，仅研究协议 |

上游在 `references/` 保留只读 shallow clone，Git 忽略。可用 `git -C references/<name> rev-parse HEAD` 核对。

2026-10-24 补核对了指挥官实际安装的 `[三改打印机] litematica-printer-mc1.21.1-1.3-beta.4-local.jar`，fabric 版本 `1.3-beta.4-1790562282161-local`，SHA-256 `8eb0d291afecfe344c649b39a69b0a59b10ee2eace5033f2b2c6f4928c1e4c57`。该版本从玩家 Inventory 索引 9 开始查找潜影盒，取料/回塞使用两次普通 PICKUP，且会紧接关闭窗口；与当前上游 HEAD 的 QUICK_MOVE/确认逻辑不同。兼容判断以实际安装版本为准。本次仅读取 JAR 元信息/字节码观察协议行为，没有改动或重新分发客户端模组。

参考官方 [Paper 项目说明](https://docs.papermc.io/paper/dev/project-setup/) 与 [下载 API](https://docs.papermc.io/misc/downloads-service/)。目标锁定 Paper **1.21.1 build 133**，SHA-256 `39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9`。

本地测试服额外运行插件来源：指挥官指定正式服的 `plugins/PaperAccurateBlockPlacement-1.2.0.jar`、`plugins/ProtocolLib.jar` 与精准放置 config，只读下载保存在 `.cache/placement-protocol/`。前者 SHA-256 `5cfff9c34433562b6ce26078bfa2d7a91b32a5c5f91144b187712659f1d889dc`；后者为 ProtocolLib 5.4.0，SHA-256 `ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf`。独立插件依赖 ProtocolLib，未将两者打入 CloudStorage JAR。当前 [hxrry27 上游](https://github.com/hxrry27/PaperAccurateBlockPlacement) 已面向较新 MC，测试以下载的 1.2.0 产物和真实 1.21.1 行为为准。

客户端依赖兼容升级使用 [Masa Gadget 4.0.403](https://github.com/plusls/MasaGadget/releases/tag/4.0-patch.10)，1.21.1 Fabric JAR SHA-256 `63dd766edeed573346d0b3ddbcf52da65e4c5806200b7b50d0a82277913540ce`；以及 [MagicLib 0.8.882](https://github.com/Hendrix-Shen/MagicLib/releases/tag/0.8-patch.17)，1.21.1 Fabric 稳定完整 JAR SHA-256 `229ba558d5ad4876edb8484add0da74aa70f3062842a0f978a732c2c926ce035`。摘要与官方发布附件 digest 一致；这些客户端依赖未打入 CloudStorage 插件。

2026-10-25：另建可修改客户端分支目录 `D:/Code/Java/litematica-printer-cloud`，基于上述 water2004 v1.1.5 commit，保留 AGPL-3.0、署名和完整构建源码。按需通道和回填改动位于该客户端项目，没有复制进独立 Paper 插件；完整对应源码随本地发行包提供。上述“没有修改客户端”的说明只针对 2026-10-24 的历史检查。

中文名称取自指挥官已安装的 Minecraft 1.21.1 官方本地资产 `assets/indexes/17.json` 所引用的 `minecraft/lang/zh_cn.json`，对象 SHA1 `c4ea30a988baf02b7e2a1ac11ba1b252423c4a35`。只提取 block/item 名称共 1785 项到 `src/main/resources/names/zh_cn.properties`，不使用在线翻译或模组自定义物品表。
