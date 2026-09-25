# 第三方来源与许可声明 / Third-Party Notices

本文件列出 **Carpet-FLT-Addition** 在开发过程中参考或使用的第三方项目与依赖，
以及各自的许可条款与使用方式。

依照各开源许可证的要求（保留版权声明、注明来源与修改），特此集中声明。
本项目的完整许可证见 [LICENSE](LICENSE)（GNU LGPL-3.0）。

---

## 一、参考/移植的第三方项目

### 1. Carpet-Org-Addition

- **项目地址**：https://github.com/fcsailboat/Carpet-Org-Addition
- **版权**：Copyright (c) 2024 fcsailboat
- **许可证**：MIT License（全文见本文末）

**经逐行比对，本项目仅有 1 个源文件构成代码移植（占全部源文件约 2%），其余均为独立实现：**

| 涉及文件 | 使用方式 |
|---|---|
| `fakeplayer/BlockExcavator.java` | **代码移植**（唯一）。已改写变量名、适配各版本 API、移除上游未使用的 public 方法、重写中文注释；**每个版本的文件顶部均保留了完整的 MIT 版权与许可声明** |
| `fakeplayer/AbstractFakePlayerAction.java` | 参考接口设计思路，**独立编写，未复制代码** |
| `fakeplayer/FakePlayerUtils.java` | 参考部分方法的实现思路，**独立编写，未复制代码** |
| `fakeplayer/LibrarianTradeFindAction.java` | 参考动作流程设计，**独立编写，未复制代码** |

> 依据 MIT 许可，Copyright 声明与许可声明必须随软件的所有副本或实质部分保留。
> 因此 `BlockExcavator.java` 顶部的声明块**不可删除**。
> 本项目全部 18 个版本目录（1.16.5 ~ 26.3，含 `origin` 模板）均已带上该声明块；
> 打包时 `THIRD-PARTY-NOTICES.md` 也会一并放入 jar（见 `build.gradle` 的 `jar {}` 块）。

### 2. Carpet-LMS-Addition

- **项目地址**：https://github.com/Citrus-Union/Carpet-LMS-Addition
- **许可证**：GNU GPL-3.0

**本项目仅参考其设计思路，未复制其任何代码。**
（经全项目行级比对：本项目与其最高代码重合度为 **4%**，且均为 `import` 语句与通用 Java 惯用写法。）

| 涉及文件 | 参考内容 |
|---|---|
| `fakeplayer/MultiContainerWithdrawAction.java` | 跨多个箱子取货的整体流程思路 |
| `fakeplayer/ContainerWithdrawAction.java` | 贪心选槽策略（本项目采用线性扫描，实现方式不同） |
| `fakeplayer/BulkFetchRepackAction.java` | 取货阶段的划分思路 |
| `storage/StackCounter.java` | 库存计数规则（本项目独立实现） |

> 由于未复制代码，GPL-3.0 的传染性（copyleft）条款**不适用于本项目**。

---

## 二、编译期依赖

以下依赖未被修改，按其自身许可证使用：

| 依赖 | 许可证 | 说明 |
|---|---|---|
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Apache-2.0 | 模组加载器 |
| [Fabric API](https://github.com/FabricMC/fabric-api) | Apache-2.0 | Fabric 官方 API |
| [Fabric Carpet](https://github.com/gnembon/fabric-carpet) | MIT | 本模组的前置 |
| [MixinExtras](https://github.com/LlamaLad7/MixinExtras) | MIT | 通过 `include(...)` 一并打包 |

---

## 三、Minecraft 原版代码

本项目在实现部分功能时（例如 `mixin/vehicle/MinecartLeashMixin.java`）会参照
**Minecraft 原版类**（如 `AbstractBoat`）的结构。原版 Minecraft 代码的使用受
[Minecraft EULA](https://www.minecraft.net/en-us/eula) 约束，不属于本文件所列的第三方开源项目范畴。

---

## 附：MIT License 全文

```
MIT License

Copyright (c) 2024 fcsailboat

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

*如发现遗漏或标注有误，欢迎提交 issue 指正。*
