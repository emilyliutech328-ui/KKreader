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

### 没有配 Secrets 会怎样

`release.yml` 会直接失败并报「缺少 KEYSTORE_BASE64」，**不会**产出未签名的包。
想临时绕过：本地构建后手动创建 Release、手动传 APK 即可。

