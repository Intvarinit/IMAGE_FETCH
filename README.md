# IMAGE_FETCH

Android 端相机取图 App：通过 **Wi‑Fi PTP/IP**（或 USB PTP）连上相机，浏览卡内照片、把 JPEG 原图取回本机，
并支持 Canon EOS 的**实时取景 + 遥控拍摄 + 曝光三要素/对焦控制**。

零 AndroidX 依赖，仅用系统 API：`compileSdk 35` / `minSdk 26` / Java 17，`versionName 0.8.0`（`versionCode 19`）。

## 已验证 / 对接的相机

| 机型 | 判定依据 | 说明 |
| --- | --- | --- |
| **Canon EOS RP** | `GetDeviceInfo(0x1001)` 的 Model = `Canon EOSRP`，握手名 `EOSRP` | 当前主力测试机。属**普通 EOS**，快门走半按 `(1,0)` → 全按 `(2,0)` |
| Canon EOS M50 Mark II | Model = `Canon EOS M50m2` | 早期主力测试机。属 **EOS M 系列**，快门只发 `RemoteReleaseOn(3,0)` |

机型判定必须用 `GetDeviceInfo` 读到的 Model 串前缀（`is_canon_eos_m()`，libgphoto2 `ptp-private.h:118-135`），
**不能只看响应码**：EOS RP 会「接受」EOS M 的 `(3,0)` 并回 `0x2001` 却根本不释放快门，
所以机型记忆只有在**相机确实上报了新对象**之后才被采纳（见 `PtpIpTransport#awaitObjectAdded`）。

参考实现：`libgphoto2/camlibs/ptp2`（同仓库内），代码注释里引用的行号均指该目录下的文件。

## 连接方式

- **Wi‑Fi PTP/IP**：默认端口 `15740`。发起方用持久化 GUID 标识自己，重连时相机才会认作同一设备。
  Wi‑Fi 路径下**不发送** `CloseSession(0x1003)`——实测会让相机停掉 PTP/IP 监听服务，之后连接报 `ECONNREFUSED`。
- **USB PTP**：走 `UsbPtpTransport` + `UsbPtpFraming`，不依赖 AndroidX/UsbManager 之外的东西。
- 断线重连退避：`1/2/4/8/15/25/30/30s`（累计约 115s）。心跳优先用真实命令往返，
  主动 PING 不可靠（部分相机不响应 PING 但业务命令正常）。

## Canon EOS 用到的厂商命令

| 命令 | 作用 |
| --- | --- |
| `0x9114 SetRemoteMode` | 进/出遥控态。EOS M 系列取 `0x15`、普通 EOS 取 `1` |
| `0x9115 SetEventMode` | 开关事件推送；开启后**每个厂商命令前都要排空 `0x9116` 事件队列** |
| `0x9116 GetEvent` | 取事件块（TLV 序列），同时用于排空与读取属性推送 |
| `0x9110 SetDevicePropValueEx` | 写厂商属性（曝光三档、取景输出等） |
| `0x9127 RequestDevicePropValue` | 催相机把某个属性值推过来 |
| `0x9128 / 0x9129 RemoteReleaseOn/Off` | 遥控快门按下 / 松开 |
| `0x9151 / 0x9152 Initiate/TerminateViewfinder` | 开关实时取景（机型能力集支持时才发） |
| `0x9153 GetViewFinderData` | 拉一帧取景 JPEG |
| `0x9154 / 0x9160 DoAf / AfCancel` | 单次自动对焦 / 停对焦（两者必须配对使用） |
| `0x915B TouchAfPosition` | 把对焦点移到取景画面坐标 |
| `0x911D KeepDeviceOn` | 保活，防止相机在取景期间休眠 |

关键属性码：`0xD101` 快门、`0xD102` 光圈、`0xD103` ISO、`0xD1B0` EVFOutputDevice（位掩码）、
`0xD1B1` EVFMode、`0xD1BA` LvAfSystem（对焦方式）。
关键事件码：`0xC189` PropValueChanged、`0xC18A` AvailListChanged、`0x4002` ObjectAdded。

## 这些约束是踩过坑得出的，改动前请先看代码注释

- **`0xD1xx` 厂商属性的值只能从事件流里读**：标准 `0x1015 GetDevicePropValue` 对厂商属性段一律回
  `0x2005`。当前值从 `0xC189`、允许值列表从 `0xC18A` 里攒，必要时发 `0x9127` 催。
- **每个厂商命令前先排空 `0x9116`**：`SetEventMode(1)` 后相机会持续堆事件，不读就让所有厂商命令
  统一回 `0x2019 DeviceBusy`。对焦驱动未结束时（需 `0x9160` 收尾）同样会让 `0x9110` 恒回 `0x2019`。
- **关面板 ≠ 交还机身**：关面板只把画面输出收回机身屏（`EVFOutputDevice = 0x01`，保留 TFT 位），
  **不退出遥控态**。libgphoto2 那套收尾（`SetRemoteMode(0)/(1)` + `SetEventMode(0)` + `ResetUILock`）
  是给「马上要断开连接」的会话用的——我们保持会话，退遥控态反而把相机留在半状态，机身按键无响应、屏幕报 busy。
- **拍摄前暂停取景写 `0`**（彻底停画面上游），与关面板写 `0x01` 是两条不同路径，取值相反、不可混用。
- **JPG 与 RAW 取回**：JPG 直接取回；RAW 下载时优先取卡上同名 JPG（相机以 RAW+JPG 拍摄），
  没有同名 JPG 时用内置 LibRaw 在手机上显影成全尺寸 JPG（显影结果按相机白平衡/sRGB 输出，
  色彩可能略异于机内 JPG；未启用 zlib/libjpeg，个别压缩 DNG 可能显影失败）。视频不支持取回。
  **删除不受格式限制**：删除走标准 `0x100B DeleteObject`，任意格式（CR3/RAW/视频）都能选、都能删，
  删除前必须二次确认。
- **「仅 JPG」是一种分页范围，不是界面过滤**：该模式下每页就是 `pageSize` 张 JPG，总数也是 JPG 张数
  （不再先按全部对象切片、再由界面丢掉 RAW，导致一页只剩两三张）。JPG 与否只能从 ObjectInfo 的文件名
  判断，所以快速分页会按需逐个筛查并缓存结果——翻到第 N 页才扫到第 N 页所需的量，同一批句柄只扫一遍；
  尚未扫完时状态栏会注明「还有对象未筛查」（此时总数仍是已筛出的张数）。切换格式过滤器等于换分页范围，
  必须回第一页重读。
- **应用内副本在导出相册成功后必须删除**，避免占用双倍存储。

## 目录结构

```
app/src/main/java/com/imagefetch/
├── MainActivity.java                 界面与面板装配
├── CameraSessionCoordinator.java     会话生命周期、取景循环、拍摄取回编排（单线程 executor）
├── CameraConnectionService.java      前台服务，保持连接不被回收
├── transport/                        CameraTransport 接口 + PTP/IP、USB 两种实现
├── ptp/                              PTP 操作转发、存储浏览、Canon 曝光档位表
├── raw/                              LibRaw 显影封装（仅 JNI 入口）
├── transfer/                         新对象发现（事件优先 + 句柄差集）、取回、心跳、重连策略
├── storage/                          本地仓库与相册导出
└── ui/                               列表适配器
```

原生层源码在 `app/src/main/cpp/`：LibRaw 静态库、JNI 入口与 stb JPEG 编码。

## 构建与测试

需要 Android SDK 35、NDK `27.0.12077973`、CMake `3.22.1`（RAW 显影的原生库）。

```powershell
$env:JAVA_HOME='D:\devTool\jdk-21.0.12.1+1'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

许可与移植说明见 [NOTICE](NOTICE)。