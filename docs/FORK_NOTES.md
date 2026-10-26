# 分叉说明（相对上游的改动清单）

> GPL-3.0 要求保留版权声明并说明修改。本文记录轻风阅读相对上游的实质改动。

## 血缘

```
gedoor/legado (GPL-3.0)
    └── Luoyacheng/legado-E · 阅读Sigma
            └── KK读书 v3.x (本仓库早期)
                    └── 轻风阅读 1.0.x (当前)
```

## 一、品牌与身份

| 项 | 上游 | 轻风阅读 |
|---|---|---|
| 应用名 | 阅读 / 阅读Sigma | 轻风阅读（zh）/ 輕風閱讀（港台）/ QingFeng Reader（其他） |
| applicationId | `io.legado.app` | **`io.qingfeng.reader`** |
| namespace | `io.legado.app` | **不变**（与源码包结构绑定，改了全量 import 失效） |
| 自定义权限 | `io.legado.app.READ_WRITE` | `io.qingfeng.reader.READ_WRITE` |
| 版本线 | 3.x | **1.0.x**（重新起算） |
| 归档名 | legado | qingfeng-reader |

## 二、功能新增

### 1. Edge 在线音色听书（`service/edge/`）

- `EdgeProtocol.kt` — Sec-MS-GEC 时间证明算法
- `EdgeTts.kt` — WebSocket 合成客户端（speech.config + SSML + 二进制音频帧）
- `EdgeTtsEngine.kt` — 适配层，复用上游 `HttpReadAloudService` 的
  ExoPlayer / SimpleCache / MediaSession，**只替换合成方式**
- 音色选择对话框，22 个内置音色做离线保底，支持自定义 SSML
- 连续失败自动回落系统 TTS

> 协议实现参照 [rany2/edge-tts](https://github.com/rany2/edge-tts)（LGPL-3.0）的公开算法。

### 2. 视觉

- `lib/theme/GlassUi.kt` — 玻璃拟态（Android 12+ RenderEffect，低版本退化半透明渐变）
- `lib/theme/Monet.kt` — 莫奈壁纸取色（自研：4096 桶量化 + 饱和度/明度打分 + HSL 校正）

### 3. 性能分级

- `help/PerfHelper.kt` — 按 `isLowRamDevice` / 内存 / 核心数分三档
- 立体书架、玻璃模糊按档位自动降级，设置里可强行覆盖

### 4. 界面

- 立体书架顶部轮播（`ui/main/bookshelf/carousel/`）
- 书架底部改为「全部图书 · N 本」虚线区域
- 订阅页可折叠使用说明
- 内置轻小说文库默认书源（`assets/defaultData/wenku8Sources.json`）

## 三、构建修复

### 1. versionCode 不再依赖 git

原实现 `versionCode = 20000 + gitCommits`，而 `gitCommits` 取
`git rev-list HEAD --count`。源码无 `.git` 时返回空串回退 0，
导致 versionCode 停滞，新包无法覆盖安装。

改为 `300_000 + yyMMddHH`，单调递增、不依赖 git。

### 2. KSP 增量可开启

上游注释里关掉 `ksp.incremental` 是为绕开跨盘报错。
现在通过把 `GRADLE_USER_HOME` 放到与项目同盘解决，增量已开启。

## 四、安全加固（沿用 KK读书 时期）

- UMD 解压上限
- Rhino 脚本超时
- 正则超时
- 默认校验证书（不跳过 TLS）
- 移除 Firebase analytics/perf 依赖与 `google-services.json`
- 移除上游遗留 workflows（其中含公开测试密钥 `legado.jks`）

## 五、未改动的部分

以下均为上游原样，以便未来同步上游更新：

- `model/analyzeRule/` 规则引擎（CSS/XPath/JSONPath/正则/JS）
- `model/webBook/` 书源调度
- `model/localBook/` 本地格式解析（TXT/EPUB/MOBI/PDF/UMD）
- `data/` Room 实体与 DAO
- `ui/book/read/` 阅读器核心排版
- `modules/book/`、`modules/rhino/`、`modules/web/`

## 六、同步上游

```bash
git remote add upstream https://github.com/Luoyacheng/legado-E.git
git fetch upstream
git merge upstream/main   # 注意冲突主要在 strings.xml / build.gradle
```

⚠️ 合并时务必复查：
- `app/build.gradle` 的 `applicationId` / `versionCode` 计算
- 四份 `strings.xml` 的 `app_name`
- `AndroidManifest.xml` 的自定义权限名
