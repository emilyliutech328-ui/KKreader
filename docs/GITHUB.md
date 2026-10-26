# 轻风阅读 · Git / GitHub 管理方案

> 目标：把当前"没有版本控制"的源码目录，变成可追溯、可回滚、可发布、可协作的 GitHub 仓库
> 现状诊断基于实测，见第一节

---

## 一、现状诊断（为什么必须做这件事）

### 1.1 当前是"无版本控制"状态

```
C:\kkbuild  (→ D:\AI项目\轻风阅读\kk-reader 的联接)
├── .git                ❌ 不存在
└── 6468 个文件，92.6 MB（排除构建产物）
```

**这带来的实际后果**（不是理论风险，已经踩到）：

| 后果 | 具体表现 |
|---|---|
| **versionCode 恒为 20000** | `versionCode = 20000 + gitCommits`，而 `gitCommits` 靠 `git rev-list HEAD --count`，无 `.git` → 命令失败 → 回退 0 → **下一版装不上** |
| **改坏了回不去** | 这一轮书架几何改错了两回，全靠我手工 edit 反向改回来；如果有 git，`git checkout -- .` 一秒解决 |
| **无法对比版本差异** | 想知道"这次和上次改了什么"，只能翻聊天记录 |
| **无法发版** | GitHub Release、changelog、版本标签都依附于 git tag |
| **协作无从谈起** | 别人提 PR 你没法 merge |

### 1.2 目录里的"脏数据"（必须先清）

实测发现工作树里混进了调研阶段的临时文件：

| 路径 | 体积 | 性质 | 处理 |
|---|---|---|---|
| `catraw/` | **71.5 MB** | 我抓文库页面的原始 HTML 样本 | ❌ 不入库，删或挪到外部 |
| `py8/` | 0.4 MB | 下载的第三方 Python 库源码 | ❌ 不入库 |
| `p2test/` `cat/` | 0.8 MB | 抓取测试临时目录 | ❌ 不入库 |
| `sm.xml` | 0.6 MB | 站点 sitemap 快照 | ❌ 不入库 |
| `app/build/` `.gradle/` `.kotlin/` `build/` | **约 547 MB** | 构建产物 | ❌ 已在 .gitignore（但 app/build 需确认） |
| `local.properties` | 极小 | 含本机 SDK 路径 | ❌ 必须不入库 |

> `app` 目录 563.6 MB，去掉 `build/` 后只有 **15.9 MB** —— 也就是说 **97% 是构建产物**。
> 真正需要入库的源码约 **16 MB / 约 1900 个文件**，这个体积对 GitHub 完全健康。

### 1.3 敏感信息检查（好消息：无泄露）

- ✅ **签名密钥库不在项目内**（`.jks` 在 `C:\Users\01\WorkBuddy\...\keystore\`，项目外）
- ✅ 签名口令在 `~/.gradle/gradle.properties`（用户级，不在项目内）
- ⚠️ `local.properties` 含本机 SDK 绝对路径，已不入库

**结论：可以安全建库，无需清洗历史。**（因为没有历史，一切从零开始）

---

## 二、仓库设计

### 2.1 仓库形态：Fork 还是独立仓库？

轻风阅读的代码谱系是：

```
gedoor/legado  (GPL-3.0)
    └── Luoyacheng/legado-E  (阅读Sigma)
            └── 你的 KK读书（已发布 3.26.x）
                    └── 轻风阅读（本次，1.0.x）
```

**推荐：独立仓库（standalone repo），不做 fork。**

理由：
1. 已经改了包名（`io.qingfeng.reader`）、应用名、图标、目标用户，**产品定位已独立**
2. 做了较多本土化定制，与上游差异大，fork 的 PR 通道实际上用不上
3. **GPL-3.0 只要求保留版权声明和开源**，不要求必须 fork 上游
4. 独立仓库便于自己控制 Issues / Releases / Actions

**但必须在 README 显著位置写明血缘**（见 2.3），这是 GPL 的合规要求，也是对上游的尊重。

### 2.2 仓库命名

```
qingfeng-reader            ← 主仓库（推荐）
```

- 不用中文名（GitHub URL 友好、clone 兼容性好）
- 不用 `KK读书`（已废弃的品牌）
- 描述里写中文全称

### 2.3 README 必备结构

```markdown
# 轻风阅读 (QingFeng Reader)

Android 本地优先阅读器：自带书源引擎、Edge 在线音色听书、立体书架、莫奈取色。

## 血缘与许可
本项目的阅读引擎、书源系统、本地书解析等核心能力源自：
- gedoor/legado (GPL-3.0)
- Luoyacheng/legado-E / 阅读Sigma
本项目在其基础上做品牌定制、界面改造与功能扩展，同样以 GPL-3.0 开源。
改动清单见 docs/FORK_NOTES.md。

## 下载
（Releases 链接）

## 功能
（引用 功能全景与界面优势.md）

## 构建
（见 docs/BUILD.md）

## 许可
GPL-3.0，见 LICENSE
```

> ⚠️ **GPL-3.0 合规要点**：
> - 保留原 `LICENSE` 文件不动
> - 源码必须公开可获取（这正是建 GitHub 仓库的意义）
> - 修改过的文件需要保留版权声明
> - 若发布 APK，需同时提供对应源码（用 Release 里的 Source code 即可）

---

## 三、初始化步骤（推荐方案 A：从零建干净仓库）

### 3.1 方案对比

| 方案 | 做法 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| **A. 新建干净仓库**（推荐） | 在干净副本上 `git init`，首个提交即完整代码 | 干净、无脏历史 | 需要先清目录 | ✅ 当前情况 |
| B. 就地 init 后清洗 | 直接 `git init` 再删脏文件 | 一步到位 | 首次提交会包含脏文件（若顺序错） | 不推荐 |
| C. 以 KK读书 仓库为基底 | 继承旧仓库历史 | 保留 v3.x 历史 | 旧仓库带 KK 品牌、包名 | 可选

> 若你之前已经把 **KK读书** 推到过 GitHub，建议用**方案 C 的变体**：
> 从旧仓库 clone → 改名为新仓库（GitHub Settings → Rename）→ 再推轻风阅读的改动。
> 这样 v3.x 的历史不会丢。**请告诉我旧仓库是否已存在**，会改变具体操作。

### 3.2 方案 A 的具体命令

```bash
# 第 1 步：准备干净副本（不要就地操作，保留可回退）
# 假设干净副本在 D:\AI项目\轻风阅读\repo
mkdir -p "D:/AI项目/轻风阅读/repo"
robocopy "C:/kkbuild" "D:/AI项目/轻风阅读/repo" /E ^
  /XD build .gradle .kotlin .git .idea catraw py8 p2test cat ^
  /XF sm.xml local.properties /NFL /NDL /NJH /NJS /NP

# 第 2 步：补 .gitignore（见 3.3）
# 第 3 步：初始化
cd "D:/AI项目/轻风阅读/repo"
git init -b main
git add -A
git commit -m "chore: 初始化轻风阅读 1.0 源码快照

基于 legado-E (GPL-3.0) 的本地优先 Android 阅读器。
本次提交为 1.0.26.102613 的完整源码基线。"

# 第 4 步：关联远程并推送
git remote add origin git@github.com:<你的账号>/qingfeng-reader.git
git push -u origin main
```

### 3.3 完整 `.gitignore`

在现有基础上补齐（**加粗为新增**）：

```gitignore
# === 现有 ===
*.iml
.gradle
local.properties
.DS_Store
/build
build/
/captures
.externalNativeBuild
/release
/tmp
node_modules/
/app/app
/app/google
/app/gradle.properties
package-lock.json
.idea/
.kotlin/
.cxx
.vscode

# === 新增：构建与工具 ===
**/build/
*.apk
*.aab
*.ap_
*.dex
*.idsig
*.hprof
captures/
.cxx/

# === 新增：签名与密钥（绝对不能入库）===
*.jks
*.keystore
key.properties
**/keystore/

# === 新增：调研/抓取残留（本次实际问题）===
catraw/
py8/
p2test/
cat/
sm.xml
*.repo.zip

# === 新增：本地调试 ===
/app/src/main/assets/web/vue/node_modules/
.gradle-cache/

# === 新增：系统 ===
Thumbs.db
desktop.ini
```

> ⚠️ **`.gitignore` 里 `/app/google` 这条要保留**——上游用它排除 `google-services.json`，
> 我们的自建版已经删了这个文件，但规则留着防止误提交。

---

## 四、分支策略

对一个**单人主导、周期发版**的 App 项目，**不要上 Git Flow**（太重）。
推荐 **简化版 GitHub Flow**：

```
main          ← 随时可构建、可发布的稳定线
  ↑ 合并
dev           ← 日常开发（可选，单人可直接在 main 上做）
  ↑
feat/xxx      ← 单个功能/修复
fix/xxx
```

### 4.1 单人项目的实用做法

| 场景 | 做法 |
|---|---|
| 日常小改动 | 直接在 `main` 上提交（单人没必要开分支） |
| 大改动（如封面系统重写 6 步） | 开 `feat/cover-v2` 分支，做完合并 |
| 修 P0 bug | 开 `fix/versioncode`，修完立刻合并 + 打 tag |
| 紧急回滚 | `git revert` 或回到上个 tag |

> **关键原则**：`main` 上**每一个提交都应该是能构建成功的**。
> 现在构建要 6~13 分钟，所以建议**本地构建通过再提交**，不要提交半成品。

### 4.2 分支命名

```
feat/cover-v2              新功能
fix/versioncode-static     修 bug
perf/carousel-graphics     性能
refactor/cover-generator   重构
docs/readme                文档
chore/bump-deps             杂务
```

---

## 五、提交规范（Conventional Commits）

### 5.1 格式

```
<type>(<scope>): <描述>

[可选正文]

[可选脚注]
```

### 5.2 类型与示例

| type | 用途 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(cover): 横排自适应版式 + 8 种图案` |
| `fix` | 修 bug | `fix(build): versionCode 改用时间戳，不再依赖 git` |
| `perf` | 性能 | `perf(carousel): 纹理改为按字节计缓存上限` |
| `refactor` | 重构（不改行为） | `refactor(cover): 抽出 CoverGenerator` |
| `docs` | 文档 | `docs: 补充订阅页使用说明` |
| `chore` | 杂务 | `chore: 清理调研抓取残留` |
| `build` | 构建/依赖 | `build: 升级 compileSdk 到 36` |
| `revert` | 回滚 | `revert: 撤销书架上浮夹紧改动` |

### 5.3 中文还是英文？

**两者都用，但保持一致性**：
- `type` 和 `scope` 用英文（工具链友好，能自动生成 changelog）
- 描述用中文（自己看得懂、Issue 里好引用）

```
feat(wenku8): 增加书源详情页解析
fix(carousel): 选中书被右邻遮挡，改为最后绘制
```

### 5.4 为什么值得规范

因为后面要用**自动生成 Release Notes**（见第七节），
`conventional-changelog` 这类工具靠 type 前缀来分类。写规范了，发版说明自动就有了。

---

## 六、版本号与 Tag（**这一节直接解决 P0**）

### 6.1 语义化版本

```
1.0.26.102613
│ │ │    └── 构建时间戳（yy.MMddHH，现有逻辑）
│ │ └────── 补丁号
│ └──────── 次版本（新功能）
└────────── 主版本（不兼容变更）
```

由于 App 是持续迭代、用户不感知"语义"，建议简化为：

```
v1.0.x  ← 正式版
v1.1.0  ← 有较大新功能
```

### 6.2 Tag 命名

```bash
git tag -a v1.0.0 -m "轻风阅读 1.0.0 首发"
git push origin v1.0.0
```

### 6.3 与 versionCode 的关系（**必须修**）

当前：
```groovy
versionCode 20000 + gitCommits   // gitCommits 恒为 0
```

**修复后建议**：

```groovy
// 方案 1：时间戳（推荐，单调递增，无需 git）
def buildTime = new Date().format("yyMMddHH", TimeZone.getTimeZone("GMT+8")) as int
versionCode = 300_000 + buildTime     // 例：326102613 → 过了 2100 年会溢出，但 App 寿命内安全
```

或者：

```groovy
// 方案 2：手动维护（最可控）
versionCode 20100   // 每次发版手动 +1
versionName "1.0.1"
```

> **推荐方案 1**，因为现在是 `yy.MMddHH` 时间戳命名，和 versionCode 天然同步，
> 不需要人记着改。

⚠️ versionCode 上限是 **2100000000**（2^31-1）。
`300000 + 26102613 = 26402613`，远低于上限，安全。

---

## 七、GitHub 功能使用

### 7.1 必开的功能

| 功能 | 用途 | 优先级 |
|---|---|---|
| **Releases** | 发版 + 附 APK + 自动生成 changelog | ⭐⭐⭐ |
| **Actions** | 自动构建 APK、跑单元测试 | ⭐⭐⭐ |
| **Issues** | 问题追踪（可自用做 TODO） | ⭐⭐ |
| **Wiki / docs/** | 使用说明、构建指南 | ⭐⭐ |
| **Discussions** | 用户交流（可选） | ⭐ |
| Projects | 看板（单人可不用） | ⭐ |

### 7.2 Actions 工作流（建议两个）

**① 构建检查（PR / push 到 main 时）**

```yaml
# .github/workflows/build.yml
name: Build
on:
  push:
    branches: [ main ]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - name: 单元测试
        run: ./gradlew testAppDebugUnitTest
      - name: 构建 Debug APK
        run: ./gradlew assembleAppDebug
```

> ✅ **实测更正**：本仓库 `.github/` 下**没有 `workflows/` 目录**
> （KK读书 分支已移除上游的 CI 配置），现存只有：
> - `ISSUE_TEMPLATE/`（bug / feature 模板，可留用）
> - `dependabot.yml`（依赖更新，可留用）
> - `scripts/`（`cronet.sh`、`lzy_web.py`、`tg_bot.py`，是上游辅助脚本，可留可删）
>
> 也就是说：**CI 是从零加，不存在"精简旧 workflow"的问题**。

**② 发布（打 tag 时）**

```yaml
# .github/workflows/release.yml
name: Release
on:
  push:
    tags: [ 'v*' ]

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # ... 同上 setup ...
      - name: 构建 Release APK
        run: ./gradlew assembleAppRelease
      - uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/app/release/*.apk
          generate_release_notes: true
```

> ⚠️ **签名问题**：Release 构建需要密钥库。**绝不能把 .jks 提交到仓库**。
> 正确做法：把密钥库转成 base64 存到 **GitHub Secrets**，CI 里解码。
> ```yaml
> - name: 还原密钥库
>   run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > release.jks
> ```
> 若不想搞这些，就**本地构建、手动上传**到 Release，CI 只做 debug 构建。

### 7.3 Release 规范

每次发版：
1. 更新 `CHANGELOG.md`（或让 CI 自动生成）
2. 打 tag `v1.0.1`
3. Release 说明写清：新增功能 / 修复 / 已知问题
4. **附 APK**（arm64-v8a）
5. 说明 minSdk / 签名指纹（用户核对用）

---

## 八、docs/ 目录规划

把现有的设计文档收进仓库，形成项目资产：

```
docs/
├── BUILD.md                 构建指南（环境/命令/常见错误）
├── FORK_NOTES.md            相对上游的改动清单（GPL 合规需要）
├── FEATURES.md              功能全景与界面优势
├── DESIGN/
│   ├── cover-generation.md  封面生成设计
│   ├── carousel.md          立体书架几何说明
│   └── wenku8-source.md     内置书源说明
├── KNOWN_ISSUES.md          待办与问题记录
└── RELEASING.md             发版流程
```

> 我们这几轮产出的 4 份文档（技术选型、方案A说明、功能全景、封面设计、待办）
> 直接挪进 `docs/` 就位，不用重写。

---

## 九、执行清单（建议顺序）

| # | 动作 | 预计耗时 | 风险 |
|---|---|---|---|
| 1 | 确认是否已有 KK读书 的 GitHub 仓库 | 你回答 | — |
| 2 | 建干净副本，清理调研残留 | 5 分钟 | 低（原目录保留） |
| 3 | 补全 `.gitignore` | 2 分钟 | 低 |
| 4 | `git init` + 首次提交 | 2 分钟 | 低 |
| 5 | 建 GitHub 私有/公开仓库，push | 5 分钟 | 低 |
| 6 | **修 versionCode**（顺手把 P0 解决） | 10 分钟 | 中（改动小但要重新构建验证） |
| 7 | 新增自己的 `\.github/workflows`（**从零加**，仓库里原本没有） | 20 分钟 | 中（首次跑 CI 需调通） |
| 8 | 加 `docs/` + 规范 README | 20 分钟 | 低 |
| 9 | 打第一个 tag + 首发 Release | 10 分钟 | 低 |

---

## 十、需要你决定的四件事

1. **是否已有 KK读书 的 GitHub 仓库？**
   - 有 → 走"改名继承历史"路线，v3.x 记录保留
   - 没有 → 走方案 A 全新起库

2. **仓库公开还是私有？**
   - 公开 → 符合 GPL 精神，但代码和已发布 APK 就都公开了
   - 私有 → GPL 要求"向接收二进制的人提供源码"，私有仓库需要用其它方式履约
   - ⚠️ **GPL-3.0 下，若你分发 APK，必须能提供对应源码**。私有仓库需另想办法（比如 Release 附源码 zip）

3. **CI 要不要做自动签名发布？**
   - 做 → 需要把密钥库 base64 存进 GitHub Secrets（多一步配置）
   - 不做 → CI 只跑测试 + debug 构建，正式包本地打、手动传

4. **`.github/` 里上游留下的 workflow 怎么处理？**
   - 全删重建（干净，推荐）
   - 保留能用的（可能含 Cronet 下载等有用逻辑）

---

## 附：为什么"没有 git"会导致 versionCode 卡死（技术细节）

```groovy
def gitCommits = {
    try {
        def text = 'git rev-list HEAD --count'.execute().text.trim()
        return text ? text.toInteger() : 0
    } catch (Exception ignored) {
        return 0
    }
}
versionCode 20000 + gitCommits
```

- `'git rev-list HEAD --count'.execute()` 在无 `.git` 时输出错误到 stderr，`text` 为空
- 三元表达式走 `0` 分支
- `versionCode` 永远是 20000
- 安卓规则：**新包 versionCode 必须大于已安装版本**，否则安装失败（`INSTALL_FAILED_VERSION_DOWNGRADE`）

**所以建 git 仓库不只是"管理规范"，它直接修复了一个会导致用户无法升级的 bug。**
