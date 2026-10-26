<div align="center">

# 轻风阅读 · QingFeng Reader

**Android 本地优先阅读器** — 自带书源引擎、Edge 在线音色听书、立体书架、莫奈取色

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)
![minSdk](https://img.shields.io/badge/minSdk-21-6E4C13?style=flat-square)
![targetSdk](https://img.shields.io/badge/targetSdk-36-6E4C13?style=flat-square)

</div>

---

## 这是什么

轻风阅读是一个 Android 阅读器：**没有广告、没有统计、不运营书库**。
它提供阅读界面与本地书籍管理，书源由用户自己配置。

核心能力：

- **书源引擎** — CSS / XPath / JSONPath / 正则 / JavaScript 五种解析方式自由组合，
  支持导入导出、分组、后台校验、逐条调试
- **立体书架** — 3D 书脊轮播，按设备性能自动分档（低配只看列表，高配全开）
- **Edge 在线音色听书** — 无需账号的在线朗读，支持自定义 SSML、预缓存、锁屏控制
- **本地格式** — TXT / EPUB / MOBI / AZW3 / PDF / UMD
- **主题** — 日间/夜间/墨水屏，主色强调色逐项可调，莫奈壁纸取色
- **数据** — 完整备份、WebDAV 云同步、远程书库

详细功能见 [docs/FEATURES.md](docs/FEATURES.md)。

## 下载

前往 [Releases](../../releases) 下载最新 APK。

| 设备 | 安装包 |
| :-- | :-- |
| 近年 Android 手机/平板 | arm64-v8a |
| 旧款 32 位设备 | armeabi-v7a |

> 包名 `io.qingfeng.reader.release`，与 Legado 官方版、「KK读书」均可共存安装。

## 血缘与许可

本项目的阅读引擎、书源系统、本地书解析等核心能力并非原创，而是来自以下开源项目：

```
gedoor/legado  (GPL-3.0)
    └── Luoyacheng/legado-E · 阅读Sigma (GPL-3.0)
            └── KK读书 (本仓库早期版本 v3.x)
                    └── 轻风阅读 1.0.x
```

我们在上游基础上做了：品牌定制、界面改造（立体书架/玻璃拟态/莫奈取色）、
功能扩展（Edge 在线音色听书、性能分级）、构建修复。

本项目**遵循 GPL-3.0**，源码完全公开。改动清单见 [docs/FORK_NOTES.md](docs/FORK_NOTES.md)。

感谢 gedoor、Luoyacheng 及所有上游贡献者。

## 构建

环境：JDK 17、Android SDK 36、Gradle 8.14。

```bash
git clone https://github.com/emilyliutech328-ui/KKreader.git
cd KKreader
./gradlew assembleAppRelease
```

详细说明（含常见错误、签名配置、CI）见 [docs/BUILD.md](docs/BUILD.md)。

## 项目结构

```
app/                     主模块（UI + 书源引擎 + 服务）
modules/book/            UMD 等本地格式解析库
modules/rhino/           Rhino JS 引擎适配
modules/web/             网页版书源编辑器（Vue）
docs/                    设计与说明文档
```

## 参与

欢迎提 Issue / PR。提交前请阅读 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)。

反馈问题请附：应用版本、Android 版本、复现步骤。
**不要**附带账号、Cookie 或令牌。

## 内容与免责

- 本项目**不提供、不运营任何在线书库**，安装包不附带小说文件。
- 书源规则由用户自行添加，第三方书源内容与本项目无关。
- 小说、封面、插图归各自权利人所有；源码许可不授予内容使用权。
- 请仅阅读、导入和缓存你有权使用的内容，遵守来源站点规则，
  不得绕过访问限制、破解 DRM 或未经许可传播。

## 上游项目

- [gedoor/legado](https://github.com/gedoor/legado)
- [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E)
