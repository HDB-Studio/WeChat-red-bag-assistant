# 微信红包（WeChatLuckyMoney）

> **作者：HDB-Studio**
>
> 本项目**基于 [monkeytong/WeChatLuckyMoney](https://github.com/monkeytong/WeChatLuckyMoney) 修改**（其上游为 [geeeeeeeeek/WeChatLuckyMoney](https://github.com/geeeeeeeeek/WeChatLuckyMoney)），
> 并针对 **微信 8.x（Android）** 重新适配，遵循原项目的 MIT 许可证。

## 修改内容

- 兼容微信 8.x 聊天页（`Chatting` / `ChattingMainUI`）与红包气泡文案「微信红包 / 微信紅包」
- 兼容 `.plugin.luckymoney.ui.LuckyMoney`、`LuckyMoneyReceiveUI`、`LuckyMoneyNewReceiveUI` 等拆红包页面
- 按「開/开/拆/Open」关键字与 `contentDescription` 识别拆红包按钮，不依赖具体控件 id（微信 8.0.56+ 的 id 会随机变化）
- 不再使用 `PackageManager` 查询微信 Activity，适配 Android 11+ 包可见性限制
- 主界面新增「暂停/恢复抢红包」总开关（无障碍开启时可随时暂停，无需关闭服务）
- 新增运行日志与界面节点抓取，便于排查机型兼容问题
- 移除广告模块、更新模块与失效的第三方 SDK
- 构建升级至 Android 14（targetSdk 34）、AGP 8.7 / Gradle 8.x / JDK 17

## 使用方法

1. 安装 APK（Android 13+ 若提示受限，请先在应用信息里允许「受限设置」）
2. 打开应用 → 开启插件（无障碍服务）
3. 小米/澎湃 OS 建议把省电策略设为「无限制」并允许自启动
4. 回到微信聊天，坐等红包进账

主界面顶部按钮：`运行日志` / `抓取界面节点` / `暂停抢红包`

## 构建

```bash
export JAVA_HOME=<JDK 17>
# local.properties 中设置 sdk.dir
gradle assembleRelease
```

> 签名：仓库不包含签名密钥（`.jks` 已被忽略），自行打包时请在 `app/build.gradle` 的 `signingConfigs` 中填入自己的密钥。

## 免责声明

本项目仅供学习 Android 无障碍服务（AccessibilityService）使用。自动抢红包可能违反微信用户协议，存在被限制红包功能或封号的风险，请自行承担使用后果。

## 许可证

[MIT](LICENSE.md)，原项目版权归其原作者所有。
