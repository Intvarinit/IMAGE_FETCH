# 相机连接链路笔记（BLE 交接 + Wi‑Fi 入网 + 保活）

> 记录版本：0.8.48（提交 `2f56b53`）
> 实测设备：Canon EOS RP（热点 `EOSRP-895_Canon0A`）+ Honor AAP‑AN00 / Android 16（SDK 36）
> 协议细节来自官方 `jp.co.canon.ic.cameraconnect` 反编译（`C0183b` / `C0235o` / `C0203g` / `p018d4.V` 等）。

---

## 1. 链路总览

```
点「连接」/ 启动自动连接
  → BLE 交接：唤醒相机、让相机自己开热点，交回 SSID/密码/IP/BSSID/WTIME
  → 等 WTIME + 8s（实测热点可见比相机上报的 WTIME 还慢）
  → WifiNetworkSpecifier 入网（钉 BSSID + 声明隐藏 SSID + WPA2 密码）
  → ConnectivityManager.bindProcessToNetwork（整个进程走相机网络）
  → PTP/IP 握手 → SetRemoteMode + SetEventMode(1) 进入遥控/事件模式
  → 读卡分页 + 缩略图 + 5s 心跳保活
```

相关文件：

| 职责 | 文件 |
| --- | --- |
| BLE 交接全部逻辑 | `app/src/main/java/com/imagefetch/connect/CanonBleHandover.java` |
| 入网 / 心跳 / 重连 / 会话 | `app/src/main/java/com/imagefetch/CameraSessionCoordinator.java` |
| 连接入口、Wi‑Fi 对话框、回退分支 | `app/src/main/java/com/imagefetch/MainActivity.java` |
| 遥控模式入口 | `CameraTransport#enterRemoteMode` / `PtpIpTransport#enterRemoteMode` |

---

## 2. BLE 交接协议顺序（官方还原）

服务/特征（`0001xxxx` 为会话，`0002xxxx` 为 WAP）：

1. 写 `00010006 = [1] + App 名`，随后开 indication，等相机回 `[2]`（官方无超时，这里 4s 兜底继续）。
2. 读 `0001000B`（能力位）。`EOS RP` 实测 `0x07000000`，其中 **bit 64 未置位 = 不支持「禁止自动关机」**。
3. 订阅 `0001000C`（会话状态）、`00020003`（WAP 状态）、`00020002`（WAP 命令）。
4. 身份三连写 `0001000A`：
   - `{3} + 16 字节 UUID`（复用 PTP 的 `ptpip_initiator_guid`）
   - `{4} + 手机昵称`
   - `{5, 2}`
5. `00020002 = {10}`（REQUEST_AP_CONFIG），等 TLV 通知：
   - 通知偏移 2 起是 TLV；type 16 = IP（小端）、type 2 = BSSID、type 48 = WTIME（单位 100ms）。
6. 读 `00020004`（SSID）、`00020006`（密码）、`00020005`（安全类型列表）。
7. 五项齐备后写 `0001000A = {1}`（会话确认），再发 START：
   - `00020001` 头 4 字节含 16、且安全类型含 9 → `00020002 = {4,0,0,0,9}`
   - 否则 → `00020002 = {1}`
8. `00020003` 回 `Code=1 / SubCode=3` 才算「开始开热点」；`4/5` 表示拒绝。
9. 交接完成后 BLE 不立刻关闭：每 15s 读一次 `00010005` 维持会话（官方在 Wi‑Fi 期间也持续交互）。

关键点：

- `0001000A = {10,1}`（禁止自动关机）**只有能力位 64 置位才能发**；EOS RP 发了会 GATT 133 断链。
- 系统对 App 隐藏当前热点 BSSID（荣耀返回 `02:00:...`），所以持久化 BSSID 没用，
  **交接返回的 BSSID 是唯一可靠来源**，入网时用它钉热点。
- 已配对设备（`bondState=12`）直接开会话；未配对要先 `createBond` 等 `BOND_BONDED`。

---

## 3. Wi‑Fi 入网（Specifier 降级链）

`CameraSessionCoordinator#bindWifiNetwork`：

1. 首选：`WifiNetworkSpecifier` + `setSsid` + `setWpa2Passphrase` + `setIsHiddenSsid(true)`
   + `setBssid(交接返回的 BSSID)`（三星机型跳过 BSSID，官方也这么做）。
2. 25s 等不到 → 停 2s，用同样参数再请求一次（荣耀经常不回调 `onUnavailable`）。
3. 仍失败且是 5s 内的快速拒绝（用户取消/密码错）才继续降级：
   不限定 BSSID → 按隐藏热点 → WPA3。避免连续弹系统对话框。
4. 入网成功：`bindProcessToNetwork(network)`，再等 600ms 让 DHCP/路由就绪才发 PTP 握手。
5. 手动断开时 `bindProcessToNetwork(null)`。

注意：打开系统 Wi‑Fi 设置页会释放 App 的 Specifier 网络，导致 PTP `ECONNABORTED`；
测试时不要中途跳设置页。

---

## 4. 会话保活（三层）

| 层 | 周期 | 位置 | 说明 |
| --- | --- | --- | --- |
| PTP 心跳 | 5s | `CameraSessionCoordinator` 独立 `heartbeatExecutor` | 必须是真实 PTP 命令；退后台也继续跑 |
| BLE 会话保活 | 15s | `CanonBleHandover#sessionKeepAlive` | 读 `00010005` |
| 遥控模式 | 连上立即 | `enterRemoteMode()` = SetRemoteMode + SetEventMode(1) | 官方连上后立刻做；不做相机可能按空闲关热点 |

心跳三态（`HoldState`）只是派生状态：前台 `HOLD`、后台 `SUSPEND`（仅标记，不停心跳）、
手动断开 `RELEASE`。实测息屏 + 后台 3 分钟心跳无空窗、连接不掉。

---

## 5. 超时与失败回退

| 场景 | 时长 | 位置 |
| --- | --- | --- |
| BLE 扫描 | 12s | `CanonBleHandover.SCAN_TIMEOUT_MS` |
| BLE 连接 | 10s | `CONNECT_TIMEOUT_MS` |
| App 名回执 | 4s 兜底 | `PAIR_ACK_TIMEOUT_MS` |
| AP 配置 TLV | 3s 兜底 | `AP_CONFIG_TIMEOUT_MS` |
| START 状态指示 | 4s 兜底 | `START_STATUS_TIMEOUT_MS` |
| 交接整体 | 90s（仅首次配对用满） | `OVERALL_TIMEOUT_MS` |
| 已配对会话「无进展」看门狗 | 20s | `SESSION_PROGRESS_TIMEOUT_MS` |
| Specifier 首试 / 自动重试 | 25s + 2s + 25s | `SPECIFIER_TIMEOUT_MS` |
| 交接失败的快速回退 | 12s（未到 START）/ 25s（已发 START 单次） | `MainActivity` 回退常量 |

回退链：

- BLE 失败 → 用已保存凭据直连。若失败发生在 START 之前（蓝牙关闭、扫不到广播），
  只给 12s 短等待，不做第二轮自动重试；START 之后失败说明热点可能已在启动，等 25s 单次。
- Specifier 失败 → 扫描当前网段（258 个地址）→ 尝试上次 IP → 明确诊断弹窗。
  蓝牙关闭场景整条链路约 23s 出结果（优化前 63s）。

---

## 6. 踩坑记录

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| 相机热点 30–65s 后自动关闭 | 心跳与缩略图/能力探测共用 executor，实测 41–43s 空窗，相机按空闲超时关热点 | 心跳独立线程；去掉连上后的自动能力探测；连上立即进遥控模式 |
| 缩略图全空 | 同上，取图任务抢锁 + 相机空闲 | 同上；另加 `thumbnails ok/empty/total` 与逐张失败日志 |
| 断开后从「选择 Wi‑Fi」选上次 SSID 没效果 | 地址框被自动回填旧 IP，被当成「手填地址」→ 跳过 BLE，干等已关闭的热点 | `autoFilledHost` + `manualHost()` 区分自动回填与手填 |
| BLE 偶发不回包，卡满 90s | 只有整体超时，没有进展判定 | 已配对设备的会话初始化加 20s 无进展看门狗（opDone / MTU / 通知重置） |
| 写 `0001000A {10,1}` 后 GATT 133 | EOS RP 能力位 64 未置位 | 按 `0001000B` 能力位判断后再发 |
| 荣耀读不到热点 BSSID | 系统对 App 隐藏（`02:00:...`） | 只用交接返回的 BSSID 钉热点 |
| 打开系统 Wi‑Fi 设置页后 PTP 中断 | 系统释放了 Specifier 网络 | 避免中途跳设置页；断开时主动解绑进程网络 |
| 连接记录越攒越多 | 旧版按 SSID 存多份 | 只保留最后一次成功记录（SSID/密码/BSSID 成套） |

---

## 7. 测试方法

```powershell
# 日志
adb logcat -d -s ImageFetch ImageFetchBLE | Select-String 'handover OK|specifier|heartbeat|thumbnails|FAIL'

# 网络状态 / 扫描
adb shell cmd wifi status
adb shell cmd wifi list-scan-results

# 偏好（验证只留最后一次成功记录）
adb shell run-as com.imagefetch cat shared_prefs/image_fetch_prefs.xml

# UI 自动化
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml
adb shell input tap <x> <y>

# 强制重启（必须带 --user 0）
adb shell am force-stop --user 0 com.imagefetch
```

构建（本机默认 build 目录不可写时的绕行）：

1. 在 `app/build.gradle` 的 `plugins {}` **之后**临时加 `buildDir = rootProject.file("build-alt/app")`
   （不能放在 `plugins {}` 前面，Gradle 会报错）。
2. `$env:JAVA_HOME='D:\devTool\jdk-21.0.12.1+1'; .\gradlew.bat :app:assembleDebug --console=plain`
3. 装完删掉该行并清理 `build-alt/`。

---

## 8. 已知限制 / 后续可做

- 后台只做了 3 分钟息屏测试；更长待机（Doze 深度休眠、厂商杀进程）未压测。
  心跳跑在主线程 Handler 上，极端省电策略下仍可能被节流。
- 蓝牙关闭场景仍会先试 12s Specifier 再扫描网段；
  若要更快，可先判断「当前是否已连相机 SSID」，已连就直接发现 IP。
- 大批量下载期间相机侧的空闲判定未单独验证（下载与心跳已分线程）。
- BLE 交接的看门狗只对已配对设备生效，首次配对仍按 90s 总超时兜底。

---

## 9. 版本与提交

| 版本 | 内容 |
| --- | --- |
| 0.8.32 | 只保留最后一次成功连接记录（`17352c9`） |
| 0.8.33–0.8.45 | 官方顺序 BLE 交接、Specifier 钉 BSSID、心跳独立线程、遥控模式、缩略图修复、对话框重连修复 |
| 0.8.46 | 区分自动回填 IP 与手填 IP，断开后可重新走 BLE 交接 |
| 0.8.47 | BLE 失败快速回退（63s → 23s） |
| 0.8.48 | 已配对设备 20s 无进展看门狗（90s → 20s）（`2f56b53`） |
