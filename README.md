<p align="center">
  <img src="docs/icon.svg" width="128" height="128" alt="拾账 图标">
</p>

<h1 align="center">拾账 · SnapLedger</h1>

<p align="center">
  <b>截一张图，拾一笔账。</b>付款 / 到账截图在本机识别入账；工资、会员订阅等固定收支按时自动记
</p>

<p align="center">
  <a href="https://github.com/Jack-082/snap-ledger/actions/workflows/build.yml"><img src="https://github.com/Jack-082/snap-ledger/actions/workflows/build.yml/badge.svg" alt="构建状态"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-00A087" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-1A1A1A" alt="Android 9+">
</p>

不需要账号，不联网，不读通知、不用无障碍服务（只在你点“最新截图”时读取相册里的截图）：

```
微信 / 支付宝 / 银行 App 截图 ──分享──> 拾账 ──本机 OCR + 解析──> 确认 ──> 本地账本
固定收支规则（工资 每月10日 09:00 …） ──WorkManager 定时──> 自动入账 + 通知
```

## 功能

### 1. 截图入账

付款或收款后截图，然后任选一种方式导入：

| 入口 | 用法 |
|---|---|
| **快捷开关“拾一笔”** | 在通知栏快捷开关的编辑页添加一次；之后截图 → 下拉 → 点一下 |
| **系统分享** | 相册、微信、支付宝里“分享 → 拾账”，可一次多张 |
| **App 内** | “最新截图”或“相册多选” |

| 能识别的截图 | 识别内容 |
|---|---|
| 微信支付：支付成功页、账单详情、转账收款 | 金额、收支、商户 / 对方、时间、支付方式 |
| 支付宝：付款成功页、账单详情（支出 / 收入 / 退款） | 同上，另外直接采用支付宝的“账单分类” |
| 银行短信、银行 App 交易详情 | “收入 / 支出人民币 xx 元”，自动排除“余额” |
| **账单列表**（微信“账单”、支付宝“账单”、云闪付“我的账单”） | 一张图识别多笔，逐笔勾选后保存 |

识别全部在本机完成（Google ML Kit 中文模型打包在 APK 里），截图不上传，也不需要 Google Play 服务。解析时综合三类信息：

- **版面**：保留每个文字块的位置和大小。账单页的金额是全页字号最大的数字，这一条单独就能排除大部分干扰；左右两栏的“标签 / 值”按行对齐后再读取。
- **文字**：正负号、¥、“实付 / 付款金额 / 收入”加分，“优惠 / 原价 / 订单金额 / 余额 / 手续费”减分；修正常见误识别（¥ 认成 Y、0 认成 O、“38. 00”多了空格）。
- **历史**：记住每个商户上次用的分类，改过一次，以后同一商户自动沿用。

保存前都可以修改。防重复：同一张截图二次导入会被忽略（图片 SHA-256）；列表条目按“方向 + 金额 + 分钟 + 商户”去重；同金额、5 分钟内已有记录时提示“可能重复”并默认不勾选。交易关闭、支付失败、已全额退款的条目会提示并默认不勾选。

**识别不准时**：在确认页展开“识别文字”，点“复制识别数据”，得到这张图的文字和位置（JSON）。把它存进 `app/src/test/resources/fixtures/`、写上期望结果，就成了一个回归测试用例（格式见已有样本）。导出的数据包含截图里的文字，分享前请确认。

### 2. 固定收支定时入账

在 **固定收支** 页添加规则，例如：

| 名称 | 金额 | 周期 | 时刻 |
|---|---|---|---|
| 工资 | +15,000.00 | 每月 10 日 | 09:00 |
| 视频会员 | -25.00 | 每月 3 日 | 08:00 |
| 云存储年费 | -68.00 | 每年（起始月 2026-03） | 08:00 |

- 到点自动入账并发通知；支持每月 / 每季度 / 每年，日期填 31 表示月末。
- 补记：手机关机或应用被系统清理导致错过时，下次运行（每小时检查一次，或打开 App 时）会补上，**入账时间始终是设定时刻**。起始月份早于本月时会补记历史周期（最多 24 期）。
- 每个周期有唯一键 `rule:<id>:<yyyy-MM>`，重复运行不会重复入账；删掉自动生成的记录也不会被重新补回。

### 3. 账本

- 按月查看收入、支出、结余和支出分类占比，按天分组的明细，点击可编辑或删除。
- **导出本月 CSV**（UTF-8 BOM，Excel 直接打开中文不乱码）。

## 安装

在 [Actions](https://github.com/Jack-082/snap-ledger/actions/workflows/build.yml) 最新一次成功运行的 Artifacts 里下载 `SnapLedger-apk`；推送 `v*` 标签时会同时发布到 Releases。

正式构建使用固定签名，证书 SHA-256 为 `9db169c6ec9517af004d9cb1967b2d6359a6558b673dc738fa1c21f38f85e14a`，新版本可以直接覆盖安装，账本数据保留。

自己 Fork 构建且没有配置签名时 APK 使用 CI 临时生成的 debug 签名，每次构建签名不同，**升级前需要先卸载**（会清空账本，请先导出 CSV）。固定签名的配置方法与 [mi-health-bridge](https://github.com/Jack-082/mi-health-bridge#自行构建) 相同，密钥别名默认为 `snapledger`：

| Secret | 值 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | keystore（PKCS12）的 base64 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |

**本地构建**（JDK 17、Android SDK 35）：

```bash
gradle wrapper --gradle-version 8.11.1   # 只需一次
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

版本号由 Git 生成：`versionCode` = 提交总数，`versionName` = 最近的 `v*` 标签。

## 代码结构

```
app/src/main/java/io/github/snapledger/
├── parse/      Layout（版面：按行合并、字号）、ReceiptParser（单笔打分解析）、BillListParser（账单列表）、
│               TimeParser、TextNorm（误识别修正）、Lexicon（关键词 / 标签）、Categorizer
├── ocr/        OcrEngine：ML Kit 端侧识别 + 图片哈希
├── recurring/  RecurringRule、Recurrence（周期计算，纯函数）、RecurringWorker / Scheduler / Poster
├── data/       LedgerDb（SQLite）、Csv 导出
├── model/      Txn、Money（金额以“分”保存）
└── ui/         MainActivity（账单 / 固定收支）、ImportActivity（分享入口与确认页）、QuickImportTileService（快捷开关）
```

解析、周期计算、CSV 都是纯 Kotlin，单元测试在 `app/src/test`，CI 每次构建都会运行；`fixtures/` 下的识别数据样本由 `FixtureTest` 逐个校验。

## 限制

- 只解析人民币金额；外币账单需要手动修改。
- 截图识别依赖截图内容完整：裁掉金额或时间的截图需要手动补全；没有识别到时间时使用导入时刻。
- 数据只在本机（`allowBackup=false`），卸载即删除。请定期导出 CSV。

> 快捷开关、商户分类记忆、支付方式识别、账单列表导入参考了 [iCost](https://help.icostapp.com/) 的自动记账思路；iCost 在 Android 上通过无障碍服务读取支付页面，本项目不使用无障碍服务。
>
> 本项目与腾讯（微信支付）、蚂蚁集团（支付宝）及各银行无关联，相关名称仅用于说明兼容的截图类型。

## License

Apache-2.0
