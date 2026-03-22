# LittleBot

[中文](#chinese) · [English](#english)

---

<a id="chinese"></a>

## 中文版

### 项目简介

**LittleBot** 是一款 Android 应用（`applicationId`: `com.hardware.littlebot`），用于通过手机控制基于 **ESP32** 的小型机器人或云台类硬件。应用提供 **Jetpack Compose + Material 3** 界面，支持两种链路：

1. **蓝牙**：低功耗蓝牙 **BLE GATT**（向自定义特征写入舵机角度）或 **经典蓝牙 SPP**（串口式文本指令，与 Wi‑Fi 侧协议一致）。
2. **Wi‑Fi**：普通 **TCP 套接字**，按行发送/接收文本指令。

默认假设 ESP32 可作为热点（AP），手机连入后使用典型地址 **`192.168.4.1:8080`** 访问 TCP 服务；亦可在界面中修改为局域网内任意可达 IP 与端口。

### 功能特性

| 能力 | 说明 |
|------|------|
| **连接方式** | 底部连接面板：BLE 扫描列表、经典蓝牙设备、Wi‑Fi IP/端口配置与连接/断开。 |
| **BLE GATT** | 连接 ESP32 自定义服务后，按 **通道号** 映射到不同 **特征 UUID**，直接写入角度数值（与固件中的 Servo 特征对应）。支持 **步进延时**、**步进角度** 等运动参数特征。 |
| **经典 SPP** | RFCOMM 串口通道，发送与 Wi‑Fi 相同的 **换行结尾文本命令**。 |
| **Wi‑Fi TCP** | `WifiConnectionManager`：`PrintWriter.println` 发送、`BufferedReader.readLine` 接收；连接超时约 **5 秒**，读超时 **3 秒**（见源码）。 |
| **双舵机 UI** | 通道 **0（Yaw / 水平）**、**1（Pitch / 俯仰）** 独立角度、速度滑块；可选 **实时拖动即发送**（BLE）。 |
| **预设动作** | 内置多组关键帧动作（点头、摇头、摇摆、昂首、俯瞰、环视、巡视、致意、侦察、警觉等），按序列发送角度。 |
| **日志** | 连接状态、发送/错误等信息写入界面日志列表。 |
| **权限与引导** | `MainActivity` 运行时申请蓝牙与定位权限；未授权时提示；可检测定位服务关闭等场景并提示。 |

### 技术栈与版本

| 组件 | 版本（见 `gradle/libs.versions.toml`） |
|------|----------------------------------------|
| Android Gradle Plugin | 9.1.0 |
| Kotlin | 2.2.10 |
| Compose BOM | 2024.09.00 |
| minSdk | 24 |
| compileSdk / targetSdk | 36 |
| JDK（编译） | 11 |

主要依赖：`androidx.core`、`lifecycle`、`activity-compose`、`compose material3`、`material icons extended`。

### 项目结构（主要源码）

```
app/src/main/java/com/hardware/littlebot/
├── MainActivity.kt              # 入口、权限申请、主题与 MainScreen
├── ble/BleManager.kt            # BLE 扫描、GATT、经典 SPP、特征映射
├── wifi/WifiConnectionManager.kt # TCP 连接、收发
├── protocol/ESP32Protocol.kt    # 文本命令拼装与响应解析
├── viewmodel/ESP32ViewModel.kt  # UI 状态、连接与发送逻辑、预设动作
├── ui/screen/MainScreen.kt      # Compose 主界面
└── ui/theme/                    # 颜色、主题、字体
```

仓库根目录另有 **`sketch_mar15b.ino`**，可作为 Arduino/ESP32 固件参考，需与 APP 侧 UUID 或 TCP 协议保持一致。

### 构建与运行

```bash
./gradlew assembleDebug
# 或
./gradlew installDebug
```

使用 **Android Studio** 打开仓库根目录，选择 **Run** 运行 **`app`** 模块。

- **BLE**：务必在支持 BLE 的 **真机** 上测试；模拟器对蓝牙支持有限。
- **Wi‑Fi**：手机与 ESP32 需网络互通（同热点或同局域网）；`AndroidManifest.xml` 中 `usesCleartextTraffic="true"` 用于访问 **非 TLS** 的明文 TCP（常见局域网调试场景）。

### 权限说明

应用在清单中声明并在运行时请求（视系统版本而定）：

- **Android 12+**：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- **Android 11 及以下**：`BLUETOOTH`、`BLUETOOTH_ADMIN`
- **定位**：`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（旧系统上 BLE 扫描需要）
- **网络**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`

详情见 `app/src/main/AndroidManifest.xml`。

### 通信协议（文本 / Wi‑Fi & 经典 SPP）

指令为 **UTF-8 文本**，以 **`\n`** 结尾，固件按行解析。

| 指令格式 | 含义 |
|----------|------|
| `SERVO:<channel>:<angle>:<speed>` | 单路舵机：`channel` 0–15，`angle` 0–180，`speed` 1–100 |
| `MULTI_SERVO:<ch>:<a>:<s>\|<ch>:<a>:<s>\|...` | 多路，段之间 `\|` 分隔 |
| `QUERY_SERVO:<channel>` | 查询指定通道位置 |
| `RESET_ALL` | 全部复位（如回到 90°，以固件为准） |
| `STOP_ALL` | 急停所有舵机 |

建议设备回复：`OK:<message>` 或 `ERR:<message>`。解析辅助见 `ESP32Protocol.parseResponse`。

### BLE GATT 与固件约定（摘要）

以下为 `BleManager` 中与 ESP32 对齐的常量（修改时需 **APP 与固件同步**）：

| 项目 | UUID / 说明 |
|------|-------------|
| 自定义服务 | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| 通道 0（Yaw）特征 | `beb5483e-36e1-4688-b7f5-ea07361b26a8` |
| 通道 1（Pitch）特征 | `1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e` |
| 步进延时特征 | `a1b2c3d4-0001-4000-8000-00805f9b34fb` |
| 步进角度特征 | `a1b2c3d4-0002-4000-8000-00805f9b34fb` |
| 经典 SPP | `00001101-0000-1000-8000-00805F9B34FB` |

增加更多舵机时：在 `SERVO_CHAR_UUIDS` 与固件中同时增加通道与特征。

### 常见问题（中文）

1. **扫描不到设备**：确认蓝牙已开、已授权；Android 12+ 需附近设备/蓝牙权限；部分机型需打开定位。  
2. **Wi‑Fi 连不上**：检查 IP、端口、防火墙；ESP32 是否已监听 TCP；手机是否与 ESP32 同一网络。  
3. **BLE 已连但不动**：确认固件服务/特征 UUID 与 APP 一致；GATT 模式下写入的是数值而非文本命令（与 SPP/TCP 路径不同）。  

### 许可证

尚未指定默认开源许可证；若公开仓库，请补充 **LICENSE** 文件（如 MIT、Apache-2.0）。

---

<a id="english"></a>

## English

### Overview

**LittleBot** is an Android application (`applicationId`: `com.hardware.littlebot`) for controlling ESP32-based robots or pan/tilt hardware from a phone. The UI is built with **Jetpack Compose** and **Material 3**. Two transport paths are supported:

1. **Bluetooth**: **BLE GATT** (writes servo angles to custom characteristics, matching the firmware) or **Classic Bluetooth SPP** (RFCOMM serial, same newline-terminated text commands as Wi‑Fi).
2. **Wi‑Fi**: Plain **TCP** sockets with line-based send/receive.

The app defaults to **`192.168.4.1:8080`**, which matches a common ESP32 **SoftAP** setup; you can change IP and port in the UI for any reachable host on the LAN.

### Features

| Capability | Description |
|------------|-------------|
| **Connection UI** | Bottom sheet: BLE scan results, classic devices, Wi‑Fi host/port, connect/disconnect. |
| **BLE GATT** | After connecting to the ESP32 service, **channel indices** map to **per-servo characteristics**. Optional **step delay** and **step size** characteristics tune motion on the firmware side. |
| **Classic SPP** | Serial-style channel; sends the same **text protocol** as Wi‑Fi (with newline). |
| **Wi‑Fi TCP** | `WifiConnectionManager` uses `PrintWriter.println` / `BufferedReader.readLine`; connect timeout ~**5 s**, socket read timeout **3 s** (see source). |
| **Dual servo UI** | Channels **0 (Yaw)** and **1 (Pitch)** with angle/speed sliders; optional **live BLE updates** while dragging. |
| **Preset motions** | Built-in keyframe sequences (nod, shake, sway, lookup, dive, scan, patrol, greet, recon, alert, etc.). |
| **Logging** | In-app log for connection events and errors. |
| **Permissions** | Runtime requests from `MainActivity`; toasts and state for denied permissions or location services off. |

### Tech stack & versions

| Component | Version (`gradle/libs.versions.toml`) |
|-----------|----------------------------------------|
| Android Gradle Plugin | 9.1.0 |
| Kotlin | 2.2.10 |
| Compose BOM | 2024.09.00 |
| minSdk | 24 |
| compileSdk / targetSdk | 36 |
| JDK (compile) | 11 |

Main dependencies: `androidx.core`, `lifecycle`, `activity-compose`, Compose **Material 3**, **Material Icons Extended**.

### Project layout (main sources)

```
app/src/main/java/com/hardware/littlebot/
├── MainActivity.kt              # Entry, permissions, theme, MainScreen
├── ble/BleManager.kt            # BLE scan, GATT, classic SPP, UUID map
├── wifi/WifiConnectionManager.kt # TCP connect, read/write
├── protocol/ESP32Protocol.kt    # Text command builders, response parsing
├── viewmodel/ESP32ViewModel.kt  # UI state, connection & send logic, presets
├── ui/screen/MainScreen.kt      # Main Compose UI
└── ui/theme/                    # Colors, theme, typography
```

**`sketch_mar15b.ino`** at the repo root is a sample Arduino/ESP32 sketch; keep it in sync with app UUIDs and/or the TCP protocol.

### Build & run

```bash
./gradlew assembleDebug
# or
./gradlew installDebug
```

Open the **repository root** in **Android Studio** and run the **`app`** configuration.

- **BLE**: Use a **physical** BLE-capable device; emulators rarely support real Bluetooth.
- **Wi‑Fi**: Phone and ESP32 must be reachable to each other. `usesCleartextTraffic="true"` allows **non-TLS** TCP for typical LAN debugging.

### Permissions

Declared in the manifest and requested at runtime where applicable:

- **Android 12+**: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- **Android 11 and below**: `BLUETOOTH`, `BLUETOOTH_ADMIN`
- **Location**: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (required for BLE scan on older APIs)
- **Network**: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`

See `app/src/main/AndroidManifest.xml` for the full list.

### Text protocol (Wi‑Fi & Classic SPP)

Commands are **UTF-8 text** terminated by **`\n`**, one logical command per line.

| Format | Meaning |
|--------|---------|
| `SERVO:<channel>:<angle>:<speed>` | Single servo: `channel` 0–15, `angle` 0–180, `speed` 1–100 |
| `MULTI_SERVO:<ch>:<a>:<s>\|<ch>:<a>:<s>\|...` | Multiple segments separated by `\|` |
| `QUERY_SERVO:<channel>` | Query position for a channel |
| `RESET_ALL` | Reset all servos (e.g. to 90°, firmware-defined) |
| `STOP_ALL` | Emergency stop |

Suggested replies: `OK:<message>` or `ERR:<message>`. Use `ESP32Protocol.parseResponse` on the app side.

### BLE GATT firmware contract (summary)

Constants from `BleManager`—**change in both app and firmware** if you customize:

| Item | UUID / notes |
|------|----------------|
| Custom service | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Channel 0 (Yaw) | `beb5483e-36e1-4688-b7f5-ea07361b26a8` |
| Channel 1 (Pitch) | `1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e` |
| Step delay | `a1b2c3d4-0001-4000-8000-00805f9b34fb` |
| Step size | `a1b2c3d4-0002-4000-8000-00805f9b34fb` |
| Classic SPP | `00001101-0000-1000-8000-00805F9B34FB` |

To add servos: extend `SERVO_CHAR_UUIDS` and mirror characteristics on the ESP32.

### FAQ (English)

1. **No devices in scan**: Enable Bluetooth; grant permissions; on older Android, location may be required for scan.  
2. **Wi‑Fi connect fails**: Verify IP/port, firewall, ESP32 TCP server, and that both ends share a routeable network.  
3. **BLE connected but no motion**: Confirm service/characteristic UUIDs match; GATT mode writes **numeric angles** to characteristics, not the text commands used on SPP/TCP.  

### License

No default open-source license is set yet; add a **LICENSE** file (e.g. MIT, Apache-2.0) when you publish.
