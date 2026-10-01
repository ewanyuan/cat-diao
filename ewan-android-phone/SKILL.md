---
name: ewan-android-phone
description: 在 Windows 上通过猫叼操作已配对的安卓手机，或接收手机分享的收藏、文字和文件。首次使用时可从技能自带脚本和 APK 完成配置；适用于手机状态、设置、屏幕、应用、壁纸、文件、剪贴板与收藏送达。
---

# 猫叼

这是一个自包含的本地技能。电脑端程序在本技能的 `scripts/`，手机安装包在 `assets/cat-diao-android-1.8.apk`。根据本 `SKILL.md` 的实际绝对路径定位它们；不要假设当前工作目录或用户的磁盘盘符。仅在 Windows 本机执行。电脑和手机需在可互访的同一局域网；不要求无线调试。

## 首次使用

若 `%LOCALAPPDATA%/猫叼小窝/runtime/Scripts/python.exe` 不存在，运行 `scripts/setup.ps1`。它在当前 Windows 用户目录创建独立 Python 环境，安装 Excel 依赖，迁移能找到的旧配对与收藏，设置登录后后台接收；不会把凭据写进技能目录。若电脑没有 Python 3，先为当前用户安装 Python，再重试。普通使用不反复执行 setup。已有「猫叼小窝」电脑程序时共用其数据和接收端口，不启动第二个接收程序。

让用户在手机安装本技能 `assets/` 内的 APK 并打开猫叼。接收程序启动后，也可让手机从电脑的 `http://<局域网IP>:8793/cat-diao.apk` 下载。电脑运行 `scripts/run.ps1 phone pair --wait 60`；手机实际出现连接请求时由用户点「允许」，随后按手机内引导完成系统权限。找不到手机先检查同一 Wi-Fi、解锁状态和猫叼是否已打开，必要时从首页右上角「⋯ → 小窝连接」读取连接地址，在 `phone` 后、命令前加 `--address http://IP:8767`。不要因为一次连接失败就重新配对。

所有运行命令均以 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <本技能绝对路径>/scripts/run.ps1` 开头。`phone` 后接手机命令，`collector` 后接收藏命令。脚本自动使用 `%LOCALAPPDATA%/猫叼小窝/` 中的配对信息、收藏台账、收到的文件和可选知识库配置。不要把这些个人数据、API key 或运行时环境复制进技能包或提交到 GitHub。

## 操作手机

按请求使用 `phone status`、`diagnose`、`apps`、`open <包名>`、`volume <级别>`、`brightness <1-255>`、`send <文件>`、`clipboard-send --text <文字>`、`wallpaper <图片>`、`screen <保存路径>`、`home`、`back`、`recents`、`notifications`、`tap x y` 或 `swipe x1 y1 x2 y2`。`phone get <保存路径>` 只下载用户在猫叼里选定的文件，不代表能任意浏览手机文件。

屏幕点按前先取新截图并查看。屏幕控制与亮度等权限由用户在手机系统页面开启；受系统保护的页面可能无法截图。手机锁屏或长时间熄屏可能暂停连接，不能代替用户解锁。读取或发送电脑剪贴板内容前须有用户对该内容的明确请求，不在输出中回显敏感文字。

## 接收与收藏

手机分享的链接、发到电脑剪贴板的文字、选定发给电脑的文件，由 8793 端口的 `收藏同步.py listen` 接收。技能的 `setup.ps1` 设置登录自启；Codex 关闭后仍由该后台进程接收。若未运行且端口空闲，可运行 `scripts/start_receiver.ps1`。同一端口只运行一个接收程序，不用旧的 8791 剪贴板监听作为主要入口。

用户问「刚发的东西在哪」时，用 `collector status` 和 `%LOCALAPPDATA%/猫叼小窝/收藏台账/` 核对。手机显示「已到小窝」只表示电脑确认收到；月度 Excel 和 SQLite 记录才是电脑落盘的依据。收到的文件在 `%LOCALAPPDATA%/猫叼小窝/手机传来的文件/`。WeKnora 是可选的电脑端后续同步：只有服务地址和专用 API key 配在该用户目录的 `collector.env`、且服务可用时才尝试入库；不要把本地收件、网页全文解析与 AI 处理混为一谈。
