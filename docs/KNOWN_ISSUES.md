# 问题与待办记录

> 用法：新问题往下追加；改完移到「已解决」并注明 commit。
> 最后更新：2026-10-27

---

## 一、已解决

### 立体书架底部虚线区与书底距离偏近
**状态**：✅ 已修复（commit `23d7c69`）

- 原状：书底 `baseline = shelfBottom - 6dp`，虚线框顶在 `shelfBottom + 8dp`，
  间距仅 **14dp**，视觉发挤。
- 修复：`baseline` 改为 `shelfBottom - 13dp`，间距增至 **21dp**
  （≈14sp 字号的一半高度）。

### 选中书「缩回书架深处」，未浮出并遮挡邻书
**状态**：✅ 已修复（commit `23d7c69`）

三个原因叠加，逐条修复：

| 原因 | 原实现 | 修复 |
|---|---|---|
| **a. 上浮量被压到几乎为零** | `min(书脊*0.42, baseline - 书脊*1.14 - 4dp)` → 实际仅 ~27dp，书脊却 ~190dp | 书脊上限 `0.78→0.70`，上浮目标改为书脊的 **50%**，只受「书顶不越控件顶」约束。实测 **26.9dp → 61.2dp（2.3 倍）** |
| **b. 夹紧约束语义错误** | `maxLiftEdge = baseline - 4dp`，等于限制上提量「不高于书架线」，直接禁止浮出 | 删除该约束，只保留「不超出控件顶部（留 2dp）」 |
| **c. 绘制顺序遮挡错误** | 从左到右绘制，右邻书压住中间那本 | 改为「其余书从左到右，**选中书最后画**」 |
| **d. 横移与上提耦合** | 共用 `pull`，上浮变大后整排弧形排列被拉歪 | 解耦：`shiftX = pull * 0.30` |

- 安全校验：书顶位置 = **2dp**（不越界）；书底到虚线框 **21dp**。
- 验证：CI Build 通过（5m01s）。

### versionCode 恒为 20000，新版本无法覆盖安装
**状态**：✅ 已修复（commit `6241037`）

- 原状：`versionCode = 20000 + gitCommits`，而 `gitCommits` 取
  `git rev-list HEAD --count`。源码无 `.git` 时返回空串回退 0，
  versionCode 永远停在 20000 → `INSTALL_FAILED_VERSION_DOWNGRADE`。
- 修复：`versionCode = 300_000 + yyMMddHH`（如 26402623），
  单调递增、不依赖 git。
- 实测：APK 中 `versionCode='26402623'`。

### 构建过慢（全量 11.5 分钟）
**状态**：✅ 已优化

- 措施：Gradle 缓存迁至 `D:\gradle-home`（与项目同盘），
  开启 `parallel` / `caching` / `configureondemand` /
  `kotlin.incremental` / **`ksp.incremental`**。
- 实测：全量 11.5 分钟 → 冷启 2.3 分钟 → **增量 19.7 秒**。

### CI 无法运行（连续 4 个原因）
**状态**：✅ 已修复（CI 现已通过）

1. `gradlew` 丢可执行位（Windows 提交不保留 mode）→ exit 126
2. KSP 版本未与 Kotlin 配对（`2.3.4` vs `kotlin 2.3.10`）
3. `google{}` 的 `includeGroupByRegex("com\\.google.*")` 锁死 KSP 插件
4. **阿里云镜像返回 502，Gradle 遇 5xx 会中止尝试后续仓库**

修复：官方源前置、镜像降为兜底。详见 `docs/BUILD.md`。

### 仓库堆积 25 个危险 dependabot PR
**状态**：✅ 已处理

- 全部关闭并删分支，`.github/dependabot.yml` 收紧：
  只收补丁级、并发上限 3、AGP/Gradle/Kotlin/KSP/jsoup/Rhino/Room/Firebase 锁定。

---

## 二、待处理

### P0

*(暂无)*

### P1 · 品牌一致性

#### 桌面图标仍是「KK」字样
- **现象**：应用名已改为「轻风阅读」，但 `mipmap-xxxhdpi/ic_launcher_foreground.png`
  实际图样是深蓝「KK」字样，桌面第一眼就不对。
- **修复建议**：重新出图标（建议「风的线条 + 书页」或单字「风」），
  替换 `ic_launcher_background/foreground/monochrome` 五套 dpi。

#### 关于页 / 分享文案写死「KK读书」
- **位置**（`res/values/strings.xml`）：
  - `about_description_sigma`：「KK读书 是「阅读Sigma…」
  - `app_share_description_sigma`：「KK读书（自建版）…」
  - `fork_info_desc`：「KK读书 相对上游 legado-E…」
  - `values-zh/strings.xml:6` `receiving_shared_label`：「KK读书·搜索」
- **连带 bug**：`AboutActivity.kt:35` 用 `getString(R.string.app_name)`
  （=轻风阅读）去摘要里 `indexOf` 高亮品牌词，但正文里没有这四个字，
  **高亮永远不生效**。改文案后自动恢复，无需改代码。

#### 代码注释 / 颜色名仍写 KK
- **位置**：`mipmap-anydpi-v26/ic_launcher.xml:2` 注释、
  `values/colors.xml:5-8`（`kk_primary` / `kk_accent` 等）、
  `strings.xml:4` 注释、`AboutActivity.kt:33` 注释
- **影响**：不影响运行，纯维护性。颜色名改动成本高（多处引用），
  建议只改注释。

### P2 · 交互细节

#### 立体书架底部：翻页热区与虚线区重叠
- **根因**（`BookshelfCarouselView.kt`）：
  - `downY > height - dp(30f) && x > width * 0.62f` → 判为翻页
  - `downY > shelfBottom + dp(6f)` → 判为「点书打开」
  - 而虚线框从 `height-38dp` 画到 `height-8dp`，两者约 8dp 重叠
- **修复建议**：先判 `downY > shelfBottom`（底部标签区），再判翻页热区。

#### 订阅页使用说明有两处与实际不符
1. 文案写「菜单 → 订阅源管理 → 右下角「+」新建」，
   实际新增入口是 **topbar 菜单 `menu_add`**（`RssSourceActivity.kt:135`）。
2. 文案写「长按 → 校验」，但 **RSS 订阅源没有校验功能**
   （那是书源的批量校验），RSS 只有「调试」（`RssSourceDebugActivity`）。
- **已核实正确**：`group:` 前缀搜索属实（`RssFragment.kt:191`）、
  星标收藏属实（`menu_rss_star`）。改这两处措辞即可。

### P3 · 功能增强（可选）

#### 内置「轻小说文库」书源 `searchUrl` 为空
- **现象**：搜书时会抛「搜索url不能为空」（`WebBook.kt:57`），
  每次搜索多一条错误项。
- **背景**：站点对未登录访客关闭了列表/搜索接口，因此这条源
  目前只能打开已知书页链接（详情/目录/正文正常）。
- **选项**：① 配置登录信息恢复站内搜索；② 从搜索范围过滤掉空 `searchUrl` 的源。

#### 封面生成版式改良
- **状态**：设计已完成，见 `docs/DESIGN/03-封面生成设计.md`，未实施。
- **规划**：横排自适应 + 边框纹理 + 8 种图案 + 20 个占位作者。
- **已决策**：不提取副标题、占位作者不加提示、保留旧竖排版式。

#### 玻璃拟态覆盖范围
- **现状**：只做了阅读菜单（`ReadMenu.kt`）。
- **待定**：是否铺到书架 / 发现页 / 对话框（需动大量布局）。

---

## 三、已核实无误（不需要动）

- 书源实测可用：`/book/{aid}.htm`、`/novel/.../index.htm`、章节页均 200
- 内置书源导入链路完整：`App.kt:92` → `DefaultData.upVersion()` →
  `importBuiltinWenku8Source()`
- 权限名已同步为 `io.qingfeng.reader.READ_WRITE`
- 书城删除后无任何悬空引用
- 莫奈取色走 `recreateActivities()` 重建，配色能生效
- 订阅说明面板折叠状态持久化正确
- Edge 协议算法与独立实现逐字符一致（`EdgeProtocolTest` 3 项）
- 书源规则在真实页面上命中（`Wenku8SourceRuleTest` 4 项）
