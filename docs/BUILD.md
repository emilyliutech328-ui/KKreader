# 构建指南

## 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | 必需。低版本无法编译（core library desugaring + toolchain 17） |
| Android SDK | compileSdk **36** | 需 platforms;android-36 与 build-tools 36 |
| Gradle | 8.14.x | 由 wrapper 指定，无需手动装 |
| AGP | 8.13.2 | 见 `gradle/libs.versions.toml` |
| Kotlin | 2.3.10 | 同上 |

## 快速开始

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建
./gradlew assembleAppRelease
```

产物：`app/build/outputs/apk/app/release/qingfeng-reader_app_<版本>.apk`

## 常用命令

```bash
./gradlew assembleAppDebug          # 调试包
./gradlew assembleAppRelease        # 发布包（R8 + lint）
./gradlew compileAppDebugKotlin     # 只编译（日常验证，最快）
./gradlew testAppDebugUnitTest      # 单元测试

# 单独跑某组测试
./gradlew testAppDebugUnitTest --tests "io.legado.app.EdgeProtocolTest"
```

## 构建提速（重要）

本项目全量 release 构建约 **11 分钟**，但日常验证不需要它：

| 场景 | 命令 | 实测耗时 |
|---|---|---|
| 只改代码，验证能否编译 | `compileAppDebugKotlin` | **增量 ~20 秒** |
| 无改动重复构建 | 同上 | ~3 秒 |
| 最终出包 | `assembleAppRelease` | ~11 分钟 |

前提条件（已配在 `gradle.properties`）：
- `org.gradle.daemon=true` — **不要加 `--no-daemon`**，否则每次冷启动多花 1-2 分钟
- `org.gradle.parallel=true` / `caching=true` / `configureondemand=true`
- `kotlin.incremental=true`
- `ksp.incremental=true`

### KSP 增量与「跨盘」问题

如果 Gradle 缓存目录与项目**不在同一个盘**，KSP 增量会报：

```
this and base files have different roots: <缓存盘路径> ... and <项目盘路径>
```

解决：把 `GRADLE_USER_HOME` 设到与项目相同的盘。

```powershell
# Windows 示例：项目在 D 盘
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", "D:\gradle-home", "User")
# 把已有的 C:\Users\<你>\.gradle 整个移动过去（省去重新下载依赖）
```

⚠️ 移动后签名配置（若写在 `~/.gradle/gradle.properties`）会随之迁移，记得确认它还在。

## 签名

**不要把密钥库提交到仓库。**

本地构建：把配置写进用户级 `~/.gradle/gradle.properties`（不要写进项目内）：

```properties
RELEASE_STORE_FILE=C:/path/to/your.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

`app/build.gradle` 会检测这几个属性，存在则用其签名，不存在则用 debug 签名。

## 常见问题

### `Your project path contains non-ASCII characters`

项目路径含中文时 AGP 会拒绝构建。已在 `gradle.properties` 加：

```properties
android.overridePathCheck=true
```

若仍报错，可在 ASCII 路径上建目录联接（junction）再从那里构建。

### `Could not download xxx.aar`

首次构建需联网下载依赖（约 2.3GB）。国内建议在 `settings.gradle` 配置镜像。

### Cronet `.so` 缺失

`app/cronetlib/` 只有 jar，没有 `.so`。构建时不会自动下载，
缺失时应用会回退到普通 OkHttp（功能可用，仅性能略差）。
需要时手动执行 `./gradlew app:downloadCronet`。

### 构建在 R8 阶段很慢 / OOM

`gradle.properties` 里 `org.gradle.jvmargs` 已给到 `-Xmx4g`。
内存不足时不要并发跑别的构建。

### `Plugin [id: 'com.google.devtools.ksp', ...] was not found`

**这个坑踩过，记录完整排障路径，避免重复。**

症状：本地能构建，但 CI（干净缓存）报 KSP 插件解析失败。

三个层层叠加的原因，缺一不可地排查：

**① 版本必须配对（最容易被忽略）**

```
kotlin = "2.3.10"
ksp    = "2.3.4"    ← 错误！KSP 版本必须与 Kotlin 严格配对
```

KSP 的版本号与 Kotlin 对齐，`2.3.4` 配 `2.3.10` 不合法。
本地因为 Gradle 缓存里**恰好**有 2.3.4 的产物而「看起来能用」，
换成干净环境立刻暴露。正确写法：`ksp = "2.3.10"`。

**② `google{}` 的 content 过滤会锁死插件**

```groovy
google {
    content {
        includeGroupByRegex("com\\.google.*")   // ← KSP 组也匹配！
    }
}
```

`com.google.devtools.ksp` 命中 `com.google.*`，被限制成"只能从 Google 仓库解析"，
而 KSP 插件实际只发在 `gradlePluginPortal`。**移除 `com.google.*`**，
只保留 `com.android.*` 与 `androidx.*`。

**③ 镜像返回 502 会让 Gradle 放弃后续所有仓库（本次真凶）**

```
Failed to get resource: GET. [HTTP HTTP/1.1 502 Bad Gateway:
https://maven.aliyun.com/repository/gradle-plugin/.../ksp...pom]
```

阿里云 `gradle-plugin` 镜像对 KSP 仓库返回 **502（服务端错误）而非 404**。
Gradle 遇到 5xx 会判定该资源不可用并**停止尝试后面的仓库**，
于是排在后面的 `gradlePluginPortal` / `mavenCentral` 根本没被查到。

表现特征：报错里 "Searched in the following repositories" 列表**不完整**
（只列出镜像，没有官方源），这本身就是镜像出错的信号。

修复：**官方源前置，镜像降为兜底**：

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()      // 官方优先
        google { content { ... } }
        mavenCentral()
        // 镜像兜底
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        maven { url 'https://maven.aliyun.com/repository/public' }
    }
}
```

`dependencyResolutionManagement` 同样处理（镜像 502 也会中断依赖图解析）。

> 国内直连官方源确实慢一些，但**正确性优先**。镜像只在官方失败时兜底。

### `./gradlew: Permission denied`（CI，exit 126）

Windows 上提交时 git 不保留 Unix 权限位，`gradlew` 被记成 `100644`，
Linux runner 上无法执行。修复：

```bash
git update-index --chmod=+x gradlew
git ls-files -s gradlew    # 必须显示 100755
```

## CI

### build.yml — 推送 / PR 自动检查

`main` 有提交或来 PR 时自动跑：**单元测试 + 构建 Debug APK**，
产物（APK、失败时的测试报告）作为 Artifact 留存 7 天。
不涉及签名，不需要任何 Secrets。

### release.yml — 打 tag 自动发版

推送 `v*` 形式的 tag 时触发，自动**签名并发布 Release**。

需要预先配置 4 个 Repository Secrets（Settings → Secrets and variables → Actions）：

| Secret | 内容 | 生成方式 |
|---|---|---|
| `KEYSTORE_BASE64` | 密钥库文件的 base64 | 见下方命令 |
| `KEYSTORE_PASSWORD` | storePassword | 你建库时设的 |
| `KEY_ALIAS` | keyAlias | 同上 |
| `KEY_PASSWORD` | keyPassword | 同上 |

生成 base64（**Windows**）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks"))
```

生成 base64（**Linux/macOS**）：

```bash
base64 -w 0 release.jks
```

> ⚠️ 把输出整段粘进 `KEYSTORE_BASE64`，**不要换行**。
> 提交 Secrets 后建议删除本地临时文件。

发版流程：

```bash
git tag -a v1.0.1 -m "轻风阅读 1.0.1"
git push origin v1.0.1
```

CI 会：还原密钥库 → 写入签名配置（只在 runner 的临时 `~/.gradle`，
不进仓库）→ 构建 release 包 → `apksigner verify` 校验 →
创建 Release 并附 APK，正文里带 SHA-256。

### 已验证可用（2026-10-27）

v1.0.0 的完整发版链路已跑通，5 个关键步骤全部 success：

```
✓ 还原签名密钥库      ✓ 写入签名配置
✓ 构建 Release APK    ✓ 校验签名
✓ 发布到 GitHub Release
```

产物：`qingfeng-reader_app_1.0.26.102709.apk`

### ⚠️ 密钥库备份（最重要）

**`KEYSTORE_BASE64` 一旦丢失，将无法发布可覆盖安装的新版本。**

密钥库现在有两处：本地文件 + GitHub Secrets。请额外做一份离线备份：

- 本地路径：`C:\Users\01\WorkBuddy\2026-09-19-00-09-38\keystore\lanying-reader.jks`
- 建议再复制到：加密压缩包 / 网盘 / 冷备 U 盘
- 同时记下 storePassword、keyAlias、keyPassword（**不要和 jks 放在一起**）

证书指纹（核对用）：

```
SHA256: D2:94:08:53:3E:9E:B4:FC:65:39:FC:96:9D:6C:7A:15:C4:18:F3:F9:E3:61:A9:75:BB:42:5F:2B:91:5E:6B:F6
```

### 换密钥库的后果

若换用新密钥库，签出来的包**无法覆盖安装**现有用户版本
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），必须让用户卸载重装 —— 会丢数据。
所以密钥库要长期保管，务必别丢。

