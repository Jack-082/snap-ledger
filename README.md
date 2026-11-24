<p align="center">
  <img src="docs/icon.svg" width="128" height="128" alt="截图记账 图标">
</p>

<h1 align="center">SnapLedger · 截图记账</h1>

<p align="center">
  付款 / 到账后<b>截图分享</b>即可入账；工资、会员订阅等<b>固定收支</b>按时自动记
</p>

<p align="center">
  <a href="https://github.com/Jack-082/snap-ledger/actions/workflows/build.yml"><img src="https://github.com/Jack-082/snap-ledger/actions/workflows/build.yml/badge.svg" alt="构建状态"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-00A087" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-1A1A1A" alt="Android 9+">
</p>

不需要账号，不联网，不读通知、不用无障碍服务：

```
微信 / 支付宝 / 银行 App 截图 ──分享──> 截图记账 ──本机 OCR + 解析──> 确认 ──> 本地账本
固定收支规则（工资 每月10日 09:00 …） ──WorkManager 定时──> 自动入账 + 通知
```

## 功能

### 1. 截图入账

付款或收款后截图，在相册（或微信、支付宝的图片预览）里点 **分享 → 截图记账**，也可以在 App 里点 **导入截图** 从相册多选。

| 能识别的截图 | 识别内容 |
|---|---|
| 微信支付：支付成功页、账单详情、转账收款 | 金额、收支方向、商户 / 对方、时间 |
| 支付宝：付款成功页、账单详情（支出 / 收入 / 退款） | 同上，另外识别“花呗”等支付渠道 |
| 银行短信、银行 App 交易详情 | “收入 / 支出人民币 xx 元”，自动排除“余额” |

- 文字识别用 Google ML Kit 中文模型，**模型打包在 APK 里，在本机运行**，截图不会上传，也不需要 Google Play 服务。
- 解析不写死某个 App 的版式，而是给每个候选金额打分：正负号、¥、“实付 / 付款金额 / 收入”等标签加分，“优惠 / 原价 / 余额 / 手续费”减分。所以优惠券、银行余额不会被当成金额。
- 自动归类（餐饮 / 交通 / 购物 / 会员订阅 / 工资 / 退款 …），保存前都可以修改。
- 防重复：同一张截图第二次导入会被忽略（按图片 SHA-256）；同金额、5 分钟内已有记录时提示“可能重复”（例如同一笔支付的成功页和账单详情都截了图）。

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
├── parse/      ReceiptParser（金额 / 方向 / 商户 / 时间打分解析）、OcrRows（按视觉行合并 OCR 结果）、Categorizer
├── ocr/        OcrEngine：ML Kit 端侧识别 + 图片哈希
├── recurring/  RecurringRule、Recurrence（周期计算，纯函数）、RecurringWorker / Scheduler / Poster
├── data/       LedgerDb（SQLite）、Csv 导出
├── model/      Txn、Money（金额以“分”保存）
└── ui/         MainActivity（账单 / 固定收支）、ImportActivity（分享入口与确认页）
```

解析、周期计算、CSV 都是纯 Kotlin，单元测试在 `app/src/test`，CI 每次构建都会运行。识别不准的截图，可以在确认页展开“识别文字”，把文字整理成测试用例加到 `ReceiptParserTest`。

## 限制

- 只解析人民币金额；外币账单需要手动修改。
- 截图识别依赖截图内容完整：裁掉金额或时间的截图需要手动补全；没有识别到时间时使用导入时刻。
- 数据只在本机（`allowBackup=false`），卸载即删除。请定期导出 CSV。

> 本项目与腾讯（微信支付）、蚂蚁集团（支付宝）及各银行无关联，相关名称仅用于说明兼容的截图类型。

## License

Apache-2.0
