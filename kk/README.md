# KK读书 — 自建分叉说明（fork manifest）

本仓库是「KK读书」的源码仓库，由开源项目 **阅读Sigma（legado-E）** 分叉而来，
在保留上游全部能力的基础上，做品牌定制与安全加固，可独立安装、与官方版共存。

## 一、基线信息

| 项目 | 值 |
| --- | --- |
| 上游项目 | 阅读Sigma / `Luoyacheng/legado-E` |
| 上游基线提交 | `8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c`（2026-08-01，master HEAD） |
| 上游来源 | 由 `legado-E-beta.zip` 解压导入（原工作树无 `.git`） |
| 基线核对 | 未改动文件（README.md / api.md / CHANGELOG.md / App.kt）与上游 `8b87c5a` 逐字节一致 |
| 本地分支 | `main` |
| 上游远程 | `upstream` -> `https://github.com/Luoyacheng/legado-E` |

> 原 `gedoor/legado` 主仓已于 2026-05 因侵权公告下架，社区由 Sigma / Beta 等分支接棒。

## 二、本分叉的定制清单

### 1. 品牌与共存
- **独立包名** `io.lanying.reader`（`app/build.gradle` 中 `applicationId`），
  release/debug 分别加后缀 `.release` / `.debug`，可与官方 `io.legado.app` **共存安装、互不覆盖**。
  `namespace` 仍保持 `io.legado.app`（与源码包结构及 R 类绑定，未改动）。
- **应用名**改为「KK读书」，覆盖：`values`、`values-zh`、`values-zh-rHK`、`values-zh-rTW`
  四个 `strings.xml`（中文机型会走 `values-zh`，必须一并改，否则显示仍是旧名）。
- **启动图标**全新重绘：自适应图标（background / foreground / monochrome 三层）+ 传统 PNG，
  内容控制在 66dp 安全圆内，五种密度齐全。
- **自定义权限** `io.lanying.reader.READ_WRITE`，保护级别 `signature`。

### 2. 构建可复现
- `settings.gradle` 增加阿里云镜像仓库（gradle-plugin / google / public），
  解决国内拉取 `google()`、`mavenCentral()` 超时问题。

### 3. 安全加固（P1，均已落地）
| 风险 | 位置 | 处置 |
| --- | --- | --- |
| UMD 解压炸弹 / 死循环 | `modules/book` 的 `UmdUtils.java` | 新增 `MAX_DECOMPRESS_SIZE = 100MB` 上限；修正 `inflater` 循环退出条件（`count==0 && (needsInput()\|\|needsDictionary())`），附零字节连续计数兜底，`finally` 中 `end()` |
| Rhino JS 无法取消（恶意书源卡死） | `modules/rhino` 的 `RhinoContext.kt` / `RhinoScriptEngine.kt` | 新增 `scriptDeadline` 看门狗；顶层入口（`recursiveCount==1`）设置 `maxScriptRunTimeMs = 60s`，在 `ensureActive()` 中校验 |
| 正则灾难性回溯（ReDoS） | `AnalyzeByRegex.kt` / `AnalyzeRule.kt` / `TextFile.kt`，新增 `utils/RegexTimeout.kt` | 用 `TimeoutCharSequence` 包装 `CharSequence`，每 65536 次 `get()` 校验截止时间，超时抛 `RegexTimeoutException` 并被捕获为空结果 |

> 说明：`RegexTimeout` 的实现依据是 Android 框架的 `Matcher` 通过 `CharSequence.charAt()` 逐字符取值
> （已用 `javap -c` 对 android-36 的 `android.jar` 验证），因此可在取值回调处中断匹配。

### 4. 界面定制

- **配色**：新增品牌色 `kk_primary` / `kk_primary_dark` / `kk_accent` / `kk_accent_night`
  （`values/colors.xml`），浅色主题主色改为深墨蓝、强调色改暖金；`ThemeConfig.kt` 的默认色回退同步调整，
  并在 `assets/defaultData/themeConfig.json` 内置一套「KK读书」主题。
- **启动页**（`activity_welcome.xml`）：标题改用应用名，副标题「静心阅读 · 万千故事」，底部署名换成自建版说明。
- **关于页**：`AboutActivity` 摘要中高亮应用名；「我的 → 其他」新增「分叉说明」入口
  （读取 `assets/forkInfo.md`，即本文件）。
- **书架「立体书架」**：新增 `BookshelfCarouselView`（`ui/main/bookshelf/carousel/`），
  在书架顶部横向展示封面：3D 透视叠错、三倍数据实现无限循环、`LinearSnapHelper` 惯性吸附，
  吸附换书时触发轻触觉反馈。可在「书架设置」中用「立体书架」开关关闭，关闭后只保留原有列表/网格。
  样式二书架（`fragment_bookshelf2.xml`）与样式一的各分组页（`fragment_books.xml`）均已接入。

## 三、构建环境

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.14.4 |
| AGP | 8.13.2 |
| Kotlin | 2.3.10 |
| compileSdk / minSdk / targetSdk | 36 / 21 / 36 |

本地构建：`./gradlew :app:assembleRelease`（release 走 R8 混淆，并用自建 keystore 签名）。
`local.properties` 为本机 SDK 路径，已被 `.gitignore` 排除，**不要提交**。

> Windows 下若项目位于含中文的路径，AGP 需要 `android.overridePathCheck=true`；
> 若 Gradle 缓存与项目不在同一盘符，KSP 增量会报 `this and base files have different roots`，
> 需要 `ksp.incremental=false`。这两项已写入 `gradle.properties`。

## 四、同步上游更新

当前初始提交**没有与上游共享 git 历史**（原工作树是 zip 解压而来，基线提交对象未入库），
因此不能直接 `git rebase upstream/master`。推荐的对齐方式：

```bash
# 1) 拉取上游（本机网络对 github.com 的 git 隧道偶发 502，失败重试或更换网络即可）
git fetch upstream

# 2) 用 graft 把我们的初始提交"接"到上游基线 8b87c5a 上，从而建立共同祖先
git replace --graft <本仓库初始提交> 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c

# 3) 此后即可正常 rebase 到上游最新
git rebase upstream/master
```

`git replace` 产生的是 replace ref，不改变原提交，可随时 `git replace -d` 撤销。
若暂不需要对齐历史，也可新建 `upstream` 跟踪分支后用
`git merge --allow-unrelated-histories` 或 `git diff` 人工审阅后合并。

## 五、已知事项

- `.github/workflows/` 来自上游，包含 `release.yml`、`test.yml`、`cronet.yml`、`web.yml`、`stale.yml`
  以及一个上游公开的签名密钥 `legado.jks`。**本分叉使用自建 keystore 签名，与该密钥无关**。
  若不需要上游 CI，建议删除该目录，避免推送后工作流因缺少 secrets 而报错。
- 本项目继承上游 **GPL-3.0** 许可（`LICENSE` 未改动），二次分发请保留许可与署名。
