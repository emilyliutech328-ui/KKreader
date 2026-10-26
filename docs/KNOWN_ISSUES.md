# 待办与问题记录

> 用法：新问题往下追加，改完在「状态」打勾并注明版本。
> 注意：以下问题**均未修改**，等确认后统一处理，不要单独动手。

---

## 待修改（未开始）

### 1. 立体书架底部虚线区域与书的距离偏近

- **状态**：未修改（按用户要求先记录，暂不动手）
- **提出时间**：2026-10-26
- **现象**：立体书架里，最后一排书的底部与下方「全部图书 · N 本」虚线框之间距离偏小，看着有点挤。
- **期望**：再拉开 **约半个字的高度**（≈ 7~8dp，按 14sp 字号算）。
- **涉及位置**：
  - `app/src/main/java/io/legado/app/ui/main/bookshelf/carousel/BookshelfCarouselView.kt`
    - `onSizeChanged()` 里 `baseline = shelfBottom - dp(6f)` 与 `textBlockHeight = dp(46f)`
    - `drawCaptions()` 里 `val top = shelfBottom + dp(8f)`
  - 书架线（`drawShelfBackdrop()` 的 `baseline`）与落影也需要同步往下让一点
- **改法备忘**（下次动手时参考）：
  - 把 `textBlockHeight` 从 46dp 提到 ~54dp，或
  - 保持 46dp 不变，改为 `drawCaptions()` 的 `top = shelfBottom + dp(15f)`，
    同时把 `baseline` 上移 `dp(7f)`，让书架线和虚线框之间留出半个字的空隙
  - 注意 `liftHeight` 与 `spineMaxHeight` 依赖 `shelfBottom`，改动后需复查
    书顶是否仍不超出控件（上一版已修好，别回退）

---

### 2. 立体书架选中的书"缩回书架深处"，而不是悬浮在书架外

- **状态**：未修改（用户明确要求先记录，统一修复，不要单独改）
- **提出时间**：2026-10-26
- **现象**：
  滑动停止后，选中的那本书没有像预期那样"从书架上抽出来、悬浮在最外层"，
  反而看起来缩回了书架深处（被左右相邻书挡住 / 高度不明显 / 视觉上退到后排）。
- **期望**：
  1. 选中的书**明显高出**相邻书脊，看起来是从书架里被抽出来的；
  2. 它应当**遮挡左侧相邻的书**（由于 3D 视角是从右前方看，抽出后会压住左邻）；
  3. 悬浮状态要稳定，停在选中位时不能回弹进书架。

- **根因分析**（已定位到代码，但未改动）：
  文件：`app/src/main/java/io/legado/app/ui/main/bookshelf/carousel/BookshelfCarouselView.kt`

  a) **上浮量被夹得几乎为零**（主因）
     - `onSizeChanged()` 第 ~264 行：
       ```kotlin
       liftHeight = minOf(spineMaxHeight * 0.42f,
                          baseline - spineMaxHeight * 1.14f - dp(4f)).coerceAtLeast(0f)
       ```
       这是上一版为了"书顶不超出画框"加的夹紧。在常见屏幕高度下，
       `baseline - spineMaxHeight * 1.14f` 往往只剩很小的正值，
       导致 `liftHeight` 只有十几像素甚至接近 0，书几乎没有上浮。
     - `drawBookVolume()` 第 ~660 行又夹了一次：
       ```kotlin
       val maxLiftTop  = (spineTop - dp(4f)).coerceAtLeast(0f)
       val maxLiftEdge = (spineTop - dp(4f) + (baseline - spineTop)).coerceAtLeast(0f)
       val pull = lift.coerceAtMost(minOf(maxLiftTop, maxLiftEdge))
       ```
       其中 `maxLiftEdge = baseline - dp(4f)`，等于把上提量限制在"不高于书架线"，
       **这直接把"悬浮在书架外"的语义抹掉了**——书永远不可能高于书架线。

  b) **绘制顺序没有为选中的书让路**（次因）
     - `onDraw()` 第 ~513-532 行按 `first..last`（从左到右）顺序绘制，
       靠后的（右边的）书后画、压住左边的书。
     - 选中的书在中间，于是**右邻压住它**，视觉上像是被挤回书架里去了。
     - 正确做法应是把 weight 最大的（选中书）挪到最后绘制，让它压住左右邻居。

  c) **`pull` 同时被用于两个方向**
     - 现在 `pull` 既做"上提"（y 减去 pull），又做"向左让位"（x 减去 `pull * 0.66f`）。
     - 若只修 a) 而不修这个耦合，上提变大后横向位移也会跟着变大，书会歪出弧线。

- **修复方案（待确认后执行，三处需一起改）**：
  1. **重算 `liftHeight`**：改为按"书架可用高度"给一个足够大但安全的值，例如
     `liftHeight = (baseline - dp(6f)) * 0.45f`，再用
     `shelfTopMargin = dp(6f)`（控件顶部预留）做上限，保证 `spineTop - pull >= dp(2f)`。
     目标观感：选中书顶部比相邻书脊高约 **1/3 个书脊高度**。
  2. **去掉 `maxLiftEdge` 这一条夹紧**，只保留"不超出控件顶部"的约束
     （这个约束本身是对的，只是不该同时夹书架线）。
  3. **绘制顺序**：`onDraw()` 里把选中书（`coverWeight` 最大者）排到最后再画，
     其余保持从左到右。这样它才能遮住左邻。
  4. **`pull` 解耦**：上提量 `liftY` 与横向让位 `shiftX` 分开算，
     `shiftX = liftY * 0.35f` 左右（避免横向过度位移破坏弧形排列）。
  5. 改完必须回归验证：
     - 书顶不超出控件（上一版修好的不能回退）；
     - 滑动/松手/惯性吸附全过程中不抖动、不回弹；
     - 高配/中配/低配（`PerfHelper` 分档）下都正常；
     - 平板/折叠屏大宽度下比例仍合理。

- **关联改动**：修这个会和问题 1（虚线间距）共用 `shelfBottom` / `baseline`，
  建议**两项一起改、一次出包**，避免来回改同一段几何。

---

## 已解决

（暂无）

---

# 第二轮排查发现（2026-10-26 夜间，全部未修改）

> 以下由逐项对照源码 + 实测站点得出，按严重程度排序。
> **均未动手**，等统一修复。

## P0 · 会影响正常使用

### 3. versionCode 永远是 20000，下一版无法覆盖安装

- **状态**：未修改 · **严重**
- **根因**：`app/build.gradle` 里
  ```groovy
  versionCode 20000 + gitCommits
  ```
  而 `gitCommits` 靠 `'git rev-list HEAD --count'.execute()`，源码包**没有 `.git`**
  （已确认 `C:\kkbuild\.git` 不存在），命令失败后回退 0。
- **实测证据**：
  - 轻风阅读 1.0 → `versionCode='20000'`
  - KK读书 3.26 → `versionCode='10000'`
  两者都恰好等于各自基数，说明 `gitCommits` 一直为 0。
- **后果**：以后每次发版 versionCode 都是 20000，**安卓会拒绝覆盖安装**，
  用户必须先卸载（丢数据）。
- **修复建议**：改成时间戳或自增：
  ```groovy
  // 用构建时间生成，保证单调递增
  versionCode = (new Date().format("yyMMddHH") as Integer) + 300_000
  ```
  或简单用 `20000 + (System.currentTimeMillis() / 60000 % 100000)`。
  注意别超过 versionCode 上限（2^31-1），也别跨年溢出。

### 4. 玻璃拟态开启后，底部菜单面板内容会被模糊掉

- **状态**：未修改 · **严重**
- **根因**：`lib/theme/GlassUi.kt` 里对 View 用 `setRenderEffect(createBlurEffect(...))`。
  `RenderEffect` 模糊的是**该 View 自身及其整棵子树**，不是"背后的内容"。
- **调用处**：`ui/book/read/ReadMenu.kt:212` 对 `llBottomBg`（LinearLayout，
  里面是所有菜单按钮和文字）、`:218` 对 `titleBar`（标题文字）调用。
- **后果**：Android 12+ 且开启玻璃拟态时，**阅读菜单的按钮和文字会糊成一片**，
  低配机因为走渐变分支反而不受影响——表现成"越好的手机越糊"。
- **修复建议**（三选一）：
  1. 对**背景层**（纯色/图片 View）应用模糊，不碰承载内容的容器；
  2. 或者干脆去掉 `setRenderEffect`，只保留半透明+渐变（稳妥）；
  3. 或者把模糊做在 `vw_menu_bg` 这种纯背景 View 上。

### 5. 内置书源 searchUrl 为空，每次搜书都会报错

- **状态**：未修改 · 中等
- **根因**：
  - `assets/defaultData/wenku8Sources.json` 里 `"searchUrl": ""`；
  - `model/webBook/WebBook.kt:57`：
    ```kotlin
    if (searchUrl.isNullOrBlank()) throw NoStackTraceException("搜索url不能为空")
    ```
  - `ui/book/search/SearchScope.kt` 取源时不按 `searchUrl` 过滤，所以这条源一定会被选中并抛错。
- **后果**：用户每次搜书，结果列表里会多出一条"搜索url不能为空"的错误项，
  看起来像 App 坏了。
- **修复建议**：删掉 `wenku8Sources.json` 的 `searchUrl` 字段并给这条源设
  `enabledExplore = false`；或在 `SearchScope` 里过滤掉 `searchUrl.isBlank()` 的源
  （后者更通用，但会影响其他用户自建源的行为，需谨慎）。

## P1 · 品牌一致性

### 6. 应用名改了，但桌面图标还是「KK」

- **状态**：未修改 · 明显
- **证据**：`mipmap-xxxhdpi/ic_launcher_foreground.png` 实际图样是深蓝「KK」字样
  （已读图确认）。而 `application-label='轻风阅读'`。
- **后果**：桌面/任务栏显示"轻风阅读"，图标却是 KK，第一眼就不对。
- **修复建议**：重新出图标（建议"轻风/风的线条 + 书页"，或单字"风"），
  同时替换 `ic_launcher_background/foreground/monochrome` 五套 dpi。

### 7. 关于页 / 分享文案里写死了「KK读书」

- **状态**：未修改 · 中等
- **证据**（`res/values/strings.xml`）：
  - `about_description_sigma`：「KK读书 是「阅读Sigma（legado-E）」的自建分叉版…」
  - `app_share_description_sigma`：「KK读书（自建版）…」
  - `fork_info_desc`：「KK读书 相对上游 legado-E…」
  - `values-zh/strings.xml:6` `receiving_shared_label`：「KK读书·搜索」
- **连带 bug**：`ui/about/AboutActivity.kt:35` 用
  `getString(R.string.app_name)`（=轻风阅读）去 `indexOf` 高亮品牌词，
  但摘要正文里没有「轻风阅读」四个字 → **高亮永远不生效**。
- **修复建议**：把上述四处文案的「KK读书」改成「轻风阅读」，
  高亮逻辑自然恢复（不用改代码）。

### 8. 代码注释 / 图标注释 / 颜色名仍写着 KK

- **状态**：未修改 · 轻微
- **证据**：
  - `res/mipmap-anydpi-v26/ic_launcher.xml:2` 注释「KK读书 启动图标…KK 字样」
  - `res/values/colors.xml:5-8` 颜色名 `kk_primary` / `kk_primary_dark` /
    `kk_accent` / `kk_accent_night`
  - `values/strings.xml:4` 注释「应用名改为「KK读书」」
  - `AboutActivity.kt:33` 注释「品牌词改为「KK读书」」
- **后果**：不影响运行，但后续维护容易混。
- **修复建议**：颜色名可保留（改名要动多处引用，收益低），
  注释顺手改掉即可。

## P2 · 交互细节

### 9. 立体书架底部：翻页热区与虚线区域重叠

- **状态**：未修改 · 中等
- **根因**（`BookshelfCarouselView.kt`）：
  - 第 338 行：`if (downY > height - dp(30f) && x > width * 0.62f)` → 判为翻页
  - 第 344 行：`if (downY > shelfBottom + dp(6f))` → 判为"点书打开"
  - 而 `shelfBottom = height - 46dp`，虚线框从 `height-38dp` 画到 `height-8dp`
- **问题**：在右下角 `x > width*0.62` 且 `downY > height-30dp` 时，
  点虚线框会被当成"翻页"而不是"点书"。两个热区有约 8dp 重叠。
- **修复建议**：先判 `downY > shelfBottom`（明确属于底部标签区），再判翻页热区；
  或把翻页热区整体下移/右移避开虚线框。

### 10. 订阅页使用说明有两处与实际不符

- **状态**：未修改 · 轻微
- **不符点**：
  1. 文案写「菜单 → 订阅源管理 → 右下角「+」新建」，
     实际新增入口是 **topbar 菜单 `menu_add`**（`RssSourceActivity.kt:135`），
     不是右下角悬浮按钮。
  2. 文案写「长按 → 校验」，
     但 **RSS 订阅源没有"校验"功能**（那是书源的批量校验）。
     RSS 只有「调试」入口（`RssSourceDebugActivity`）。
- **已核实正确的部分**：`group:` 前缀搜索属实（`RssFragment.kt:191`），
  星标收藏属实（`menu_rss_star` → `RssFavoritesActivity`）。
- **修复建议**：改正这两处措辞。

## 已核实无误（不用担心）

- 书源实测仍可用：`/book/{aid}.htm`、`/novel/.../index.htm`、章节页均返回 200
  且 `<title>` 正常（用 Dalvik UA 实测通过）
- 内置书源导入链路完整：`App.kt:92` → `DefaultData.upVersion()` → `importBuiltinWenku8Source()`
- 权限名已同步改为 `io.qingfeng.reader.READ_WRITE`
- 书城删除后**无任何悬空引用**
- 莫奈取色后走 `recreateActivities()` 重建，配色能生效
- 订阅说明面板折叠状态持久化正确，默认展开符合预期

---

# 修复优先级建议

| 优先级 | 问题 | 理由 |
|---|---|---|
| **P0** | 3. versionCode 恒定 | 不修则后续版本装不上，最致命 |
| **P0** | 4. 玻璃模糊糊掉菜单内容 | 开了就是坏体验，且高端机才触发 |
| **P0** | 5. 书源搜索报错 | 每次搜书都刷错误，像 App 坏了 |
| P1 | 6. 图标还是 KK | 第一眼品牌不一致 |
| P1 | 7. 关于/分享文案写死 KK | 含一个连带的高亮失效 bug |
| P1 | 1+2（书架上浮+虚线间距） | 已记录，共用同一段几何，建议一起改 |
| P2 | 9. 翻页热区重叠 | 特定位置误触 |
| P2 | 10. 订阅说明措辞 | 文案修正 |
| P3 | 8. 注释/颜色名 | 纯维护性 |


