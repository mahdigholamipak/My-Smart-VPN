# My Smart VPN (YAS VPN) 🔐

A smart, user-friendly VPN client for Android based on Open-SSTP-Client, featuring automatic server selection, real-time ping measurement, and Iran Bypass functionality.

## Features

- 🌍 **Automatic Server Discovery** - Connects to VPN GATE servers automatically
- 📊 **Real-Time Ping Measurement** - Measures actual latency and sorts servers by speed
- 🇮🇷 **Iran Bypass** - Automatically excludes Iranian apps (banks, taxis, messengers) from VPN
- ⚡ **Smart Server Scoring** - Intelligent ranking based on speed, uptime, and success history
- 💾 **Offline Caching** - Loads cached servers for faster startup
- 🔄 **Auto Reconnection** - Automatic failover to next-best server on failure
- 📍 **Real-Time Location Display** - Shows actual IP and location after connection

## Installation

Download the latest APK from [Releases](https://github.com/mahdigholamipak/My-Smart-VPN/releases)

## Usage

1. **Quick Connect**: Tap "CONNECT" on home screen - app will automatically find the best server
2. **Server List**: Browse and select from available servers, sorted by real-time ping
3. **Iran Bypass**: Enable to route Iranian apps directly (bypassing VPN)
4. **Manual Connect**: Enter custom server hostname if needed

## Key Improvements Over Original

| Feature | Original | My Smart VPN |
|---------|----------|--------------|
| Server Selection | Manual only | Auto + sorted by ping |
| Ping Display | Static from CSV | Real-time measured |
| Iran Apps | Not supported | Auto-bypass for 60+ apps |
| Caching | None | 4-hour intelligent cache |
| Connection Info | CSV metadata | Real GeoIP lookup |

## Credits

- Based on [Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client) by kittoku
- Server list from [VPN GATE](https://www.vpngate.net/) - University of Tsukuba, Japan
- SSTP protocol compatible with [SoftEther VPN](https://www.softether.org/)

## Technical Details

- **Protocol**: MS-SSTP (Secure Socket Tunneling Protocol)
- **Authentication**: PAP, MS-CHAPv2
- **Min SDK**: Android 6.0 (API 23)
- **Target SDK**: Android 14 (API 35)

## Building

```bash
# Clone repository
git clone https://github.com/mahdigholamipak/My-Smart-VPN.git

# Build debug APK
./gradlew assembleDebug
```

## License

Licensed under MIT License. See [LICENSE](LICENSE) for details.

## Privacy

See [Privacy Policy](PRIVACY_POLICY.md) for details on data handling.

---

**Note**: This is a fork of Open-SSTP-Client with enhanced features for automated server selection and Iranian user needs.
