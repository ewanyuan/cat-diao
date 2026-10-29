# 猫叼 2.0 签名

2.0 使用新的应用包名 `io.github.ewanyuan.catdiao`，也必须使用一把新的发布签名。仓库里不放密钥，构建脚本也不会生成密钥。

## 谁来建钥匙

请主人在自己的电脑上新建一把发布密钥，并做两份备份（例如两块分开存放的盘）。钥匙丢失后，已安装的 2.0 将无法再覆盖升级。不要把 `.jks`、`.keystore` 或密码放进本仓库、聊天记录或技能目录。

示例（在仓库外执行，路径和密码换成自己的）：

```text
keytool -genkeypair -v -keystore C:\Users\YOU\keys\catdiao-release.jks -alias catdiao -keyalg RSA -keysize 2048 -validity 10000
```

然后任选一种方式告诉构建脚本，二者都不要提交：

- 复制 `android/keystore.properties.example` 为 `android/keystore.properties`，填写仓库外的 `storeFile` 以及密码。
- 或设置环境变量 `CATDIAO_KEYSTORE`、`CATDIAO_KEYSTORE_PASSWORD`、`CATDIAO_KEY_ALIAS`、`CATDIAO_KEY_PASSWORD`。

没有这些值时，调试构建和未签名的 release 编译仍可进行；不能打出可安装的正式包。

## 发布前必须主人点头

新签名不能覆盖旧的调试签名安装包。手机上的旧猫叼需要卸掉一次再装 2.0，或者先强制停止旧应用。2.0 的包名不同，可以和旧包同时出现在系统里，但两边会抢同一组端口，不能一起运行。手机上还没送出的收藏、最近列表、配对和系统权限不会跟着过去。电脑上的台账还在。

在主人明确同意这次卸载/重装之前，不要发布 2.0 安装包。
