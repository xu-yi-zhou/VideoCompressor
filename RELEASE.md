# 发版流程(GitHub Releases 自更新)

App 通过 GitHub Releases API 自动检查更新:发布 tag 约定为 `v<versionName>`(如 `v1.1`),
与 `app/build.gradle.kts` 里的 `versionName` 数值分段比较,发现新版弹窗 → 下载(带进度)
→ 调系统安装器覆盖安装。检查时机:启动进首页自动一次 + 「关于与更新」卡片手动按钮。

## 前置(仅首次)

1. 安装并认证 GitHub CLI:`winget install GitHub.cli` → `gh auth login`
2. 创建公开仓库并推送全部代码:
   `gh repo create VideoCompressor --public --source=. --remote=origin --push`
3. 把 `app/build.gradle.kts` 里的 `UPDATE_OWNER` 改成你的 GitHub 用户名
   (仓库尚不存在时 API 返回 404,App 端按「已是最新版本」显示)
4. 签名 keystore 在本机 `keystore/release.keystore`(已被 .gitignore 忽略,绝不入库),
   密码在本机 `keystore.properties`(仓库内是占位模板,真实值靠 skip-worktree 保护)。
   **两个文件务必备份到安全位置**:keystore 丢失 = 已装用户全部无法再升级,只能卸载重装。
5. 真机/模拟器首次迁移:旧 debug 签名包与正式签名不兼容,需
   `adb uninstall com.xuyizhou.videocompressor` 后再装正式包
   (卸载会清空本地数据,百度网盘需重新授权一次)。

## 每次发版

1. 改 `app/build.gradle.kts`:
   - `versionCode +1`(必须单调递增,否则系统安装器拒绝覆盖安装)
   - `versionName` 升到新版本号
2. 构建:`gradlew.bat assembleRelease`
   产物:`app/build/outputs/apk/release/app-release.apk`
3. 验签(确认非 debug 证书):
   `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
   (apksigner 在 Android SDK 的 build-tools 目录下)
4. 发布(更新内容写在 `--notes`,会同时显示在 App 的更新弹窗中):
   ```
   gh release create v1.1 app/build/outputs/apk/release/app-release.apk --title "v1.1" --notes "更新内容"
   ```
5. 手机打开 App(或手动点「检查更新」)→ 弹窗 → 下载 → 系统安装器覆盖安装。

## 注意事项

- `releases/latest` 返回最近发布、非 draft 的版本:不要创建测试用高版本 tag(如 v9.9)
  后忘记删除,它会顶替真实版本被 App 检测到。
- 高版本 tag 配旧 APK 无法验证安装(versionCode 降级会被安装器拒绝),
  安装全链路验证只能用真实 bump 的版本。
