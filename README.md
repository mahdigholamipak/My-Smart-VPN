# My Smart VPN - SSTP VPN Client

[![Android CI](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-23-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A modern, intelligent SSTP VPN client for Android with automatic server selection, real-time latency monitoring, and resilient connection handling.

## ✨ Features

### Smart Server Selection
- **Auto-Connect**: Automatically connects to the fastest available server
- **Ping-Based Sorting**: Real-time latency measurement with parallel pinging
- **Last Successful Priority**: Remembers and prioritizes previously working servers
- **Dead Server Filtering**: Automatically excludes unresponsive servers from cache

### Resilient Connection
- **15-Attempt Retry Loop**: Automatically retries with fresh ping data on failure
- **Offline Detection**: Gracefully handles no-internet scenarios without crashing
- **Seamless Server Switching**: Disconnect-then-connect workflow for server changes

### Smart Caching
- **Persistent Ping Data**: Saves latency measurements across app restarts
- **Background Pre-fetching**: Refreshes server data on app launch
- **Cross-Component Sync**: Real-time UI updates when data changes

### User Experience
- **Real-Time Latency Display**: Live ping values in server list
- **Country-Based Filtering**: Filter servers by country
- **Connected Server Highlighting**: Visual indicator for active connection

## 🚀 Getting Started

### Requirements
- Android 6.0 (API 23) or higher
- Android Studio Hedgehog (2023.1.1) or later
- Kotlin 1.9+

### Clone & Build

```bash
# Clone the repository
git clone https://github.com/yourusername/My-Smart-VPN.git
cd My-Smart-VPN

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

### Project Structure

```
app/src/main/java/kittoku/osc/
├── fragment/           # UI Fragments (Home, ServerList, Settings)
├── repository/         # Data layer (VpnRepository, ServerCache)
├── adapter/            # RecyclerView adapters
├── preference/         # Settings management
└── service/            # VPN Service implementation
```

## 🏗️ Architecture

### Connection Flow
```
1. User taps Connect
2. Check network connectivity (crash prevention)
3. Priority 1: Try last successful server
4. Priority 2: Use best ping from cached list
5. Priority 3: Cold start - ping all servers
6. On failure: Re-ping and retry (up to 15 times)
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `HomeFragment` | Main UI, connection control |
| `ServerListFragment` | Server browsing, selection |
| `VpnRepository` | Server fetching, ping measurement |
| `ServerCache` | Local persistence of server data |
| `PingUpdateManager` | Cross-fragment broadcast communication |
| `SstpVpnService` | SSTP VPN tunnel management |

## ⚙️ Configuration

### Server List Source
The app fetches servers from a CSV file. Configure the URL in `VpnRepository.kt`:
```kotlin
private const val SERVER_URL = "https://your-server-list-url/server_list.csv"
```

### Build Variants
- **Debug**: Development build with full logging
- **Release**: Production build (configure signing in `app/build.gradle`)

## 🔒 Security

- No hardcoded credentials or API keys
- All network operations use HTTPS
- VPN credentials managed via Android's secure preferences

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Based on [Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client)
- SSTP Protocol implementation
- VPN Gate for server lists
