# 猫叼

猫叼让安卓手机和 Windows 电脑在同一局域网内互传内容。手机里分享的文章链接会先留在手机，连上电脑后自动送达；电脑可用 Codex 操作已配对的手机。无需 USB 线或无线调试。

本仓库包含手机 App 源码、当前 APK，以及自带电脑接收程序的 Codex 技能 `ewan-android-phone`。安装和运行时产生的配对信息、收藏、文件与知识库密钥保存在使用者自己的 Windows 用户目录，不包含在仓库中。

## 让 Codex 安装

在 Windows 电脑的 Codex 中说：

> 请从 `https://github.com/ewanyuan/cat-diao` 安装 `ewan-android-phone` 技能，阅读它的 `SKILL.md`，帮我完成首次配置，并告诉我如何在手机安装猫叼。

Codex 应把仓库中的 `ewan-android-phone/` 放到当前用户的 Codex 技能目录，按技能说明运行一次 `scripts/setup.ps1`。首次配置需要 Python 3 和联网安装 `openpyxl`；配置完成后，接收程序随 Windows 登录启动。数据保存在 `%LOCALAPPDATA%/猫叼小窝/`。手机 APK 在 [`ewan-android-phone/assets/cat-diao-android-1.8.apk`](ewan-android-phone/assets/cat-diao-android-1.8.apk)。

手机安装并打开猫叼后，让手机和电脑接入可互访的同一 Wi-Fi。请 Codex 发起配对；手机出现连接请求时点“允许”，再按应用引导开启所需系统权限。屏幕控制、悬浮窗和修改亮度由用户在安卓系统设置中亲自开启。手机锁屏或长时间熄屏时，电脑可能无法连接；解锁后再试。

## 能做什么

- 手机分享链接到猫叼：离线时先保存在手机；送达电脑后写入按月份分开的 Excel 台账和本地数据库。
- 手机把选定文件送到电脑，或把复制的文字送到电脑剪贴板。
- 在 Codex 中询问手机状态、打开应用、发送文件或文字、设置壁纸，以及在手机允许屏幕控制后查看和点按屏幕。
- 可选连接电脑上的 WeKnora；它不是手机收件的前提。需要使用者自行配置服务地址和专用 API key。

收到的收藏在 `%LOCALAPPDATA%/猫叼小窝/收藏台账/`，文件在 `%LOCALAPPDATA%/猫叼小窝/手机传来的文件/`。手机显示“已到小窝”表示电脑确认收件；不代表网页全文已经抓取或 AI 已处理。

电脑和手机之间使用局域网 HTTP 和配对令牌。请在信任的 Wi-Fi 上使用，避免传输敏感文件。手机端依赖安卓无障碍服务实现屏幕查看与点按；可以随时在系统设置里关闭。系统保护页面和锁屏状态不保证可控。

## 开发手机 App

源码在 [`android/`](android/)；使用 Android Studio 导入该目录。构建配置采用 Android Gradle Plugin 8.7.3、Android SDK 36 和 Java 17。仓库不含签名密钥；自行构建的 APK 与这里提供的 APK 签名不同，通常不能直接覆盖安装。

这是 Windows + Android 的个人工具。当前发布包在原使用环境中运行过，尚未做全新电脑和其他安卓机型的安装验证。遇到连接问题，先确认同一 Wi-Fi、电脑防火墙允许接收程序，以及手机已解锁。

代码按 [MIT License](LICENSE) 开放。
