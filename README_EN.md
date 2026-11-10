# WeChat Lucky Money

**Author: HDB-Studio**

This project is **based on [monkeytong/WeChatLuckyMoney](https://github.com/monkeytong/WeChatLuckyMoney)** (upstream: [geeeeeeeeek/WeChatLuckyMoney](https://github.com/geeeeeeeeek/WeChatLuckyMoney)), adapted for **WeChat 8.x on Android** and distributed under the original MIT license.

## What changed

- Supports WeChat 8.x chat pages (`Chatting` / `ChattingMainUI`) and the new red packet label「微信红包 / 微信紅包」
- Supports `.plugin.luckymoney.ui.LuckyMoney`, `LuckyMoneyReceiveUI`, `LuckyMoneyNewReceiveUI`
- Finds the open button by text / `contentDescription` (`開/开/拆/Open`) instead of resource ids (WeChat 8.0.56+ randomizes ids)
- No `PackageManager` activity lookups (Android 11+ package visibility)
- New master switch on the home screen to pause / resume auto-claiming
- Built-in runtime log and UI node capture for troubleshooting
- Removed ads, update checker and dead third-party SDKs
- Modern build: Android 14 (targetSdk 34), AGP 8.7, Gradle 8.x, JDK 17

## Usage

1. Install the APK. On Android 13+, allow "restricted settings" for the app if prompted.
2. Open the app and enable the accessibility service.
3. On MIUI/HyperOS set battery saver to "No restrictions" and allow autostart.
4. Return to WeChat and enjoy.

## Disclaimer

**For personal study and research only. Commercial use is prohibited.**

- [DISCLAIMER.md](DISCLAIMER.md) — full disclaimer (purpose limitation, no warranty, limitation of liability)
- [NOTICE.md](NOTICE.md) — attribution chain and third-party licenses
- Not affiliated with, endorsed by, or authorized by Tencent or WeChat
- Auto-claiming red packets may violate WeChat's terms of service and can lead to feature restrictions or account bans. Use at your own risk
- Provided "AS IS", without warranty of any kind; authors accept no liability for damages

## License

[MIT](LICENSE.md) © 2026 HDB-Studio. Upstream copyright belongs to Zhongyi Tong (geeeeeeeeek / monkeytong).
