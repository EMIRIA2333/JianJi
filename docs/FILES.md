# 文件说明

> 每个目录/文件做什么用。想直接读代码，建议按下面顺序：**数据层 → 解析层 → 采集层 → 记账管道 → 界面**。

## 根目录

| 路径 | 说明 |
| --- | --- |
| `app/` | 应用主模块（Kotlin，viewBinding，无第三方依赖） |
| `xposed-stubs/` | Xposed / libxposed 的**编译期空壳**（`compileOnly`，不打包进 APK） |
| `gradle/wrapper/` | Gradle Wrapper（固定 8.7，保证任何机器构建结果一致） |
| `gradlew` / `gradlew.bat` | 构建入口（`./gradlew assembleDebug`） |
| `build.gradle.kts` / `settings.gradle.kts` | 工程与仓库配置（含 `:app`、`:xposed-stubs` 两个模块） |
| `gradle.properties` | JVM 参数、AndroidX 开关、允许中文路径 |
| `.gitignore` | 排除构建产物、签名、`local.properties` 等 |
| `LICENSE` | GPL-3.0 全文 |
| `NOTICE` | 版权声明 + 第三方组件（AndroidX / Material / JUnit）+ stubs 说明 |
| `README.md` | 项目介绍、技术亮点、安装、权限、FAQ |
| `ARCHITECTURE.md` | 架构与关键设计取舍 |
| `CHANGELOG.md` | 版本记录（从开发日志整理） |
| `CONTRIBUTING.md` | 新增平台 / PR 约定 / 代码规范 |
| `PRIVACY.md` | 隐私声明（无 `INTERNET` 权限 + 逐条权限用途） |
| `SECURITY.md` | 安全问题上报与边界 |
| `docs/` | 素材说明、排障手册、截图清单、本文件 |

## 应用模块 `app/src/main/java/com/jianji/app/`

| 目录 / 文件 | 说明 |
| --- | --- |
| `App.kt` | Application：初始化数据库/去重器/主题/日志；**单一后台线程**（`post`）与安全模式 |
| `core/` | **纯 Kotlin，无 Android 依赖，全部可单测** |
| `core/PaymentParser.kt` | 支付通知解析：金额/方向/商户/支付方式；平台包名与展示名总表（72 个平台） |
| `core/SmsParser.kt` | 银行短信解析（95xxx / 106 通道 + 银行语境校验） |
| `core/ReceiptParser.kt` | 账单**详情页**文本解析（微信/支付宝账单页结构） |
| `core/BillListParser.kt` | 账单**列表页**解析与批量补齐判据 |
| `core/ChatPaymentParser.kt` | 聊天页转账 / 红包（气泡状态 → 金额与方向） |
| `core/ServiceMessageParser.kt` | 服务通知类文案（收款/扣款提示） |
| `core/Categories.kt` | 分类体系：默认分类、关键词猜测、图标与配色 |
| `core/CategoryMemory.kt` | **分类学习**匹配逻辑（商户/备注/金额三维度打分，阈值 62） |
| `core/Deduper.kt` | 去重（同商户同金额短窗口）与待处理标记 |
| `core/NotificationFingerprint.kt` | 通知指纹（去分组计数、压缩空白，带通知时间戳） |
| `core/RecordPolicy.kt` | 记账决策：直接保存还是先询问确认 |
| `core/ScanPolicy.kt` | 扫描节流与节点上限（省电） |
| `core/RuntimePolicy.kt` | **统一门控**：总开关/无障碍/各功能开关 → 该跑哪些后台工作 |
| `core/HookProtocol.kt` | 与 Hook 模块的协议：token、字段名、接管名单（唯一来源）、存活判定 |
| `core/Models.kt` | `Record` / 类型 / 来源等数据模型 |
| `db/` | 数据层：`DbHelper`（手写 SQL，v7）、`RecordDao`、`CategoryMemoryDao` |
| `service/` | 采集与写入服务 |
| `service/AutoCaptureService.kt` | 无障碍服务：支付页/账单页/聊天页识别与批量同步 |
| `service/NotifyPipe.kt` | **唯一记账管道**：去重 → 解析 → 分类 → 决策 → 入库 |
| `service/NotifyListenerService.kt` | 通知使用权通道 |
| `service/RootScanService.kt` | root 通道：`dumpsys notification --noredact` 定时兜底 |
| `service/HookGuardService.kt` | 守护：Hook 存活保持 / 恢复被系统关闭的无障碍（仅在有活干时常驻） |
| `service/HookBridgeReceiver.kt` | 接收 Hook 广播（token 校验，任意广播即视为模块存活） |
| `service/RecordActionReceiver.kt` | 通知里的「保存 / 忽略 / 撤销」动作 |
| `service/BootReceiver.kt` | 开机 / 更新后恢复后台工作 |
| `service/SmsReceiver.kt` | 银行短信广播接收 |
| `hook/` | LSPosed 模块（**刻意不引用应用类**，只用 `android.*`） |
| `hook/HookPackages.kt` | 接管包名清单（**唯一来源**，应用侧与模块侧共用） |
| `hook/JianJiHook.kt` | 传统格式入口（`IXposedHookLoadPackage`）+ system_server / 应用进程 hook + 周期心跳 |
| `hook/JianJiModule.kt` | 现代格式入口（`io.github.libxposed.api.XposedModule`） |
| `ui/` | 界面：`MainActivity`（三页 ViewPager）、明细 / 统计 / 我的、记账设置、编辑页、分类账单页、日志页、悬浮卡片 |
| `view/` | 自绘控件：`BarChartView`（柱状图，支持点选/长按）、`PieChartView`（扇形），`SmoothPageTransformer` |
| `util/` | 运行时设施：`Prefs`、`Notifier`、`AppLog`（日志+崩溃捕获）、`GlassHelper`/`BlurUtil`（毛玻璃）、`BackgroundWork`（服务启停唯一入口）、`RootManager`、`ServiceHealth`、`ScrollMemory`、`CustomCategories`、CSV/XLSX 编解码等 |

## 资源 `app/src/main/res/`

| 目录 | 说明 |
| --- | --- |
| `layout/` | 页面与条目布局（viewBinding） |
| `drawable/` | 矢量图标与形状 |
| `drawable-nodpi/` | 首页头图与 5 张 AI 生成背景图（见 `docs/ASSETS.md`） |
| `values/` | 浅色配色、主题、字符串、数组（含 LSPosed 推荐作用域 `xposed_scope`） |
| `values-night/` | 深色/纯黑配色 |
| `xml/` | 无障碍服务配置、FileProvider 路径 |
| `mipmap-anydpi-v26/` | 自适应图标（背景/前景/单色三层矢量） |

## 模块声明与测试

| 路径 | 说明 |
| --- | --- |
| `app/src/main/assets/xposed_init` | 传统格式模块入口类声明 |
| `app/src/main/resources/META-INF/xposed/module.prop` | 现代格式模块元数据（min/target API 100） |
| `app/src/main/resources/META-INF/xposed/java_init.list` | 现代格式入口类 |
| `app/src/main/resources/META-INF/xposed/scope.list` | 默认作用域（81 项，与 `HookPackages.ALL` 一致） |
| `app/src/test/java/com/jianji/app/**` | **244 个纯 JVM 单元测试**（解析/去重/门控/学习/预算/编解码/平台一致性） |
| `.github/workflows/ci.yml` | CI：JDK17 + Android SDK34 → 构建 + 单测 + lint → 上传 APK |
| `.github/ISSUE_TEMPLATE/` | Bug / 功能建议模板（要求附运行日志） |
