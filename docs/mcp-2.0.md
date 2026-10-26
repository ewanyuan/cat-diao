# 猫叼 MCP

`scripts/mcp_server.py` 用标准输入输出提供 MCP。它让 AI 做「看画面、点画面的真机验证」：截图、点按、滑动、主页/返回/最近任务/通知、打开应用、发送文件、设置壁纸、查看状态。

它不替代 ADB。没有 logcat，没有断点，也不能静默安装。第一次把安装包送到手机时，需要用户在手机上点一次确认。

第一次配对见 `docs/first-connection.md`。电脑上的第一条命令是 `scripts/run.ps1 pair`；找不到时，使用手机「开始连接」页显示的地址运行 `scripts/run.ps1 pair --address http://IP:8767`。

把下面的命令加到支持 MCP 的客户端（工作目录为本技能的 `scripts/`，并已完成配对）：

```text
python mcp_server.py
```

本轮不做收藏正文的自动解析。内容在局域网上是明文，请只在信任的 Wi-Fi 上使用。
