# 参与贡献

欢迎 issue 与 PR。这个项目的代码风格比较朴素（无 ORM、无 DI、无协程），提交前请看一下下面的约定。

---

## 一、最快的贡献：新增一个平台适配

新增一个 App（银行 / 购物 / 外卖…）只需改 **4 处**，并跑测试确认一致：

| # | 文件 | 做什么 |
| --- | --- | --- |
| 1 | `app/src/main/java/com/jianji/app/hook/HookPackages.kt` | 在 `ALL` 里加包名（**唯一来源**，模块与应用都读它） |
| 2 | `app/src/main/java/com/jianji/app/core/PaymentParser.kt` | 在 `APP_NAMES` 里加 `包名 to "展示名"` |
| 3 | `app/src/main/res/values/arrays.xml` | 在 `xposed_scope` 里加同一行（LSPosed 的"推荐应用"） |
| 4 | `app/src/main/resources/META-INF/xposed/scope.list` | 加同一行（现代格式模块作用域） |

```bash
./gradlew testDebugUnitTest   # PlatformCoverageTest 会双向校验以上 4 处是否一致
```

> 为什么有测试盯着：曾经应用侧登记了 72 个平台，而 Hook 模块内部还是 17 个包的老名单，
> 结果购物/外卖/银行的通知被模块**静默丢掉**（界面显示已接管，实际没转发）。
> 现在任何一处漏改，`PlatformCoverageTest` 都会失败。

如果这个 App 的支付通知格式特殊（解析不出来），请**附上通知原文**
（金额可打码，但请保留"元/¥"与位数），我会把它做成 `PaymentParserTest` 的用例。

---

## 二、修 Bug 的流程（我自己的习惯，供参考）

这个项目里每个 bug 的修法都遵循同一条路径，PR 也请尽量照做：

1. **先拿到证据**：运行日志 / 识别记录 / LSPosed 日志 / 复现步骤；
2. **写一句根因**（不是"改了一下"，而是"因为 X 所以 Y"）；
3. **加一个会失败的测试**（能测就测；UI 类问题至少写清人工验证步骤）；
4. 修到测试通过；
5. 描述里写清**验证方式**。

---

## 三、代码约定

- **纯 Kotlin，无第三方依赖**（除 AndroidX / Material）；不要引入网络库、JSON 库、DI 框架；
- **禁止联网**：不要加 `INTERNET` 权限或任何上报（这是项目的核心承诺）；
- **解析器必须纯函数**：`core/` 下的解析逻辑不碰 `Context` / Android API，方便单测
  （`PaymentParser`、`SmsParser`、`ChatPaymentParser`、`CategoryMemory`…）；
- **数据库在后台线程**：所有 `dao` 调用必须在 `App.post { }` 里；
- **新增设置项**：在 `Prefs` 里加 getter/setter，并在 setter 里写一条 `AppLog.i("设置", ...)`（便于排查"设置被莫名改掉"）；
- **UI**：`viewBinding`，不要 `findViewById`；颜色走 `@color` 资源（要支持深浅主题）；
- **注释写"为什么"**，不要复述代码在做什么。

---

## 四、提交信息

```
<类型>: <一句话说明>

根因/背景：...
验证：...
```

类型用 `feat` / `fix` / `refactor` / `docs` / `test` / `chore`。

---

## 五、不要提交

- `local.properties`、`*.jks`、`keystore.properties`（签名与密钥）
- `build/`、`build-*/`、`dist/*.apk`（二进制不进仓库，走 Releases）
- 任何真实账单数据、真实商户截图（截图请用假数据或打码）

---

## 六、边界（不接受的 PR / 需求）

- 绕过支付、破解、伪造交易数据；
- 采集/上传用户数据、加统计 SDK；
- 针对第三方应用本身的攻击性功能。
