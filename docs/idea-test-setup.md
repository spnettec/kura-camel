# kura-camel 的 IDEA 测试配置

完整指南见 [`../../kura/docs/testing/idea-test-setup.md`](../../kura/docs/testing/idea-test-setup.md)
（前置要求、Maven 缓存、如何新增配置）。本仓库已内置共享运行配置：

| 配置 | 类型 | 用途 |
| --- | --- | --- |
| Kura Camel DSL JUnit | JUnit | `ForkDslRoutesProviderTest`，覆盖 XML / Java / YAML 三种 DSL 的路由装载（含脚本与 Vert.x 调用的定制行为） |

运行前置：本仓库 Maven 导入正常即可，纯单测无需 kura 运行目录；Maven 缓存缺
plc4x 时先在 `plc4x-yofc` 目录 `mvn install -DskipTests`。环境检查可执行
`../kura/tools/ide/setup-idea.sh`。

注意：本仓库的 Camel 组件、脚本引擎接入、控制台编辑与中英文国际化为 YOFC
定制实现（保留 `xml.data` 兼容键与历史类名），回归以本仓库 `.run` 配置为准，
不要照搬上游用例。
