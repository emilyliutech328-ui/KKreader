# 参与贡献

## 提交规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/)。
格式：

```
<type>(<scope>): <描述>
```

- `type` 与 `scope` 用英文（工具链可解析）
- **描述用中文**（便于对照 Issue）

### 常用 type

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `perf` | 性能优化 |
| `refactor` | 重构（不改变行为） |
| `docs` | 文档 |
| `chore` | 杂务 |
| `build` | 构建/依赖 |
| `revert` | 回滚 |
| `test` | 测试 |

### 示例

```
feat(cover): 横排自适应版式 + 8 种图案
fix(build): versionCode 改用时间戳，不再依赖 git
perf(carousel): 纹理改为按字节计缓存上限
```

### 破坏性改动

在 type 后加 `!`，并在正文写 `BREAKING CHANGE:` 说明影响：

```
feat!: 应用包名改为 io.qingfeng.reader

BREAKING CHANGE: 无法覆盖安装旧版，需先卸载。
```

## 分支

| 分支 | 用途 |
|---|---|
| `main` | 稳定线，**每个提交都应能构建成功** |
| `feat/xxx` | 新功能 |
| `fix/xxx` | 修 bug |
| `perf/xxx` | 性能 |

小改动可直接提交 `main`；大改动开分支。

## 提交前检查清单

```bash
# 1. 能编译
./gradlew compileAppDebugKotlin

# 2. 测试通过
./gradlew testAppDebugUnitTest

# 3. 没有误提交敏感文件
git diff --cached --name-only | grep -E "\.jks|\.keystore|local\.properties|key\.properties"

# 4. 没有误提交构建产物或临时文件
git status --short | grep -E "build/|\.apk|catraw/|\.utf8\.html"
```

## 不要提交的东西

- 签名密钥库（`*.jks` / `*.keystore` / `key.properties`）
- `local.properties`（含本机 SDK 绝对路径）
- `build/`、`.gradle/`、`.kotlin/`
- 抓取/调试用的临时文件（`.utf8.html`、`*.ps1`、`*.py` 等）
- `google-services.json`

这些均已在 `.gitignore` 中，但新增文件时仍需留意。

## Issue / PR

- 提 Issue 请附：**应用版本、Android 版本、复现步骤**
- **不要**在 Issue 里附带账号、Cookie、令牌
- PR 请保持单一主题，一个 PR 只解决一件事
- 涉及 UI 的 PR 建议附截图

## 代码风格

- 跟随现有代码风格（不额外引入 linter 规则）
- 中文注释，说明「为什么」而不是「做了什么」
- 公共 API 注意向后兼容（会被备份/导入功能依赖）

## 许可

提交即表示同意以 **GPL-3.0** 授权你的贡献。
