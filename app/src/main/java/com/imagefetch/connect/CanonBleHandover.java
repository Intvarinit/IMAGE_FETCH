package com.imagefetch.connect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 佳能相机的 BLE → Wi‑Fi 交接（handover）：复刻官方 Camera Connect 的唤醒路径。
 *
 * <p>协议来自官方 APK 反编译（com.canon.eos 的 GATT 回调与 WAP 服务）：
 * <ol>
 *   <li>按服务 UUID {@code 00010000-…-d8492fffa821} 扫描广播；</li>
 *   <li>连接 GATT、发现服务、按需配对；</li>
 *   <li>向 {@code 00020002} 写 START，等 {@code 00020003} 的状态与 {@code 00020002} 的 TLV 响应；</li>
 *   <li>TLV：type 16 = 相机 IP、type 2 = BSSID、type 48 = 等待毫秒数/100；</li>
 *   <li>读 {@code 00020004} SSID、{@code 00020005} 密码。</li>
 * </ol>
 * 相机收到 START 后会自己打开 Wi‑Fi AP，并把入网参数交回来——这正是官方
 * App「不用手动开相机 Wi‑Fi」的实现方式。BLE 连接在交接完成后保持，供上层在
 * 会话结束时调用 {@link #close()}。
 */
public final class CanonBleHandover {

    private static final String TAG = "ImageFetchBLE";

    private static final UUID SERVICE_SESSION =
            UUID.fromString("00010000-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_SESSION_STATE =
            UUID.fromString("00010005-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_SESSION_PAIR =
            UUID.fromString("00010006-0000-1000-0000-d8492fffa821");
    /** 会话指令：官方在交接就绪后向它写 {1} 表示会话确认，然后才发 START。 */
    private static final UUID CHAR_SESSION_CMD =
            UUID.fromString("0001000a-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_SESSION_INIT =
            UUID.fromString("0001000b-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_SESSION_IND =
            UUID.fromString("0001000c-0000-1000-0000-d8492fffa821");
    private static final UUID SERVICE_WAP =
            UUID.fromString("00020000-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_AP_CONFIG =
            UUID.fromString("00020001-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_CP =
            UUID.fromString("00020002-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_STATUS =
            UUID.fromString("00020003-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_SSID =
            UUID.fromString("00020004-0000-1000-0000-d8492fffa821");
    /** 官方映射：00020005 是安全类型位图，00020006 才是密码（反编译确认）。 */
    private static final UUID CHAR_WAP_SECURITY =
            UUID.fromString("00020005-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_PASSWORD =
            UUID.fromString("00020006-0000-1000-0000-d8492fffa821");
    private static final UUID SERVICE_RC =
            UUID.fromString("00030000-0000-1000-0000-d8492fffa821");
    private static final UUID SERVICE_LIVE =
            UUID.fromString("00040000-0000-1000-0000-d8492fffa821");
    private static final UUID SERVICE_STATUS =
            UUID.fromString("00080000-0000-1000-0000-d8492fffa821");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 10_000L;
    /** 写 App 名后等相机回 [2] 的上限；官方在这步没有超时，我们兜底继续。 */
    private static final long PAIR_ACK_TIMEOUT_MS = 4_000L;
    /** REQUEST_AP_CONFIG 后等相机回 TLV 的上限；超时仍继续读，让相机有机会补数据。 */
    private static final long AP_CONFIG_TIMEOUT_MS = 3_000L;
    /** START 后等 00020003 指示的上限；个别机型不回指示，兜底按参数齐全算成功。 */
    private static final long START_STATUS_TIMEOUT_MS = 4_000L;
    /** 交接完成后保持 BLE 会话活跃的间隔：官方在 Wi‑Fi 期间也一直有会话交互。 */
    private static final long SESSION_KEEPALIVE_MS = 15_000L;
    /** 首次要等系统配对弹窗由用户确认，整体窗口放宽到 90s。 */
    private static final long OVERALL_TIMEOUT_MS = 90_000L;
    /**
     * 已配对设备会话初始化的「无进展」上限：正常每次 GATT 读写都在 100ms 内回来，
     * 超过这个时间说明相机蓝牙已卡死（实测偶发不回包），没必要陪跑到 90s 总超时。
     */
    private static final long SESSION_PROGRESS_TIMEOUT_MS = 20_000L;
    private static final long MTU_TIMEOUT_MS = 800L;

    public interface Callback {
        void onStatus(String message);

        void onReady(Result result);

        void onFailed(String message);
    }

    /** 交接结果：相机 AP 的入网参数。 */
    public static final class Result {
        public final String ssid;
        public final String password;
        public final String ip;
        public final String bssid;
        /** 相机要求的等待时间（毫秒），等 AP 真正起来再入网。 */
        public final long waitMillis;

        Result(String ssid, String password, String ip, String bssid, long waitMillis) {
            this.ssid = ssid;
            this.password = password;
            this.ip = ip;
            this.bssid = bssid;
            this.waitMillis = waitMillis;
        }
    }

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cpCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;
    private BluetoothGattCharacteristic ssidCharacteristic;
    private BluetoothGattCharacteristic passwordCharacteristic;
    private BluetoothGattCharacteristic sessionInitCharacteristic;
    private BluetoothGattCharacteristic sessionStateCharacteristic;
    private BluetoothGattCharacteristic sessionIndCharacteristic;
    private BluetoothGattCharacteristic sessionPairCharacteristic;
    private BluetoothGattCharacteristic sessionCmdCharacteristic;
    private BluetoothGattCharacteristic apConfigCharacteristic;
    private BluetoothGattCharacteristic securityCharacteristic;

    /**
     * GATT 同一时刻只能有一个未完成的操作，官方流程也是一步等一步：
     * 读会话初始化 → 开会话通知/指示 → 读 AP 参数 → 写 START。
     */
    private final java.util.ArrayDeque<Runnable> gattOps = new java.util.ArrayDeque<>();
    private boolean opInFlight;
    private boolean sessionStarted;
    /** 官方在写 00010006 之后等相机回 [2]：收到后才去读 0001000B。 */
    private boolean pairAckSeen;
    private boolean pairWaitActive;
    /** REQUEST_AP_CONFIG 的 TLV 回执已到，后续 SSID/密码读取可以开始。 */
    private boolean apConfigSeen;
    private boolean apWaitActive;
    /** 0001000B 返回的能力位：决定还要等哪些 0001000C 状态。 */
    private int sessionFlags;
    private String modelName1;
    private String modelName2;
    private int batteryLevel = -1;
    private int cameraState = -1;
    private boolean sessionReadyLogged;
    /** 00020001 的 AP 配置数据：START 是否支持带安全类型看它。 */
    private byte[] apConfigData;
    /** 00020005 返回的安全类型列表（官方用它判断支持 9=WPA2）。 */
    private java.util.List<Integer> securityTypes;
    private boolean finished;
    private boolean ready;
    /** 会话初始化看门狗是否已武装：只对已配对设备生效（首次配对要等用户确认系统弹窗）。 */
    private boolean watchdogArmed;
    private final Runnable sessionProgressTimeout = () -> fail("相机蓝牙无响应，请重试");
    private final Runnable sessionKeepAlive = new Runnable() {
        @Override public void run() {
            if (finished || !ready) return;
            if (gatt != null && sessionStateCharacteristic != null) {
                Log.i(TAG, "会话保活：读 00010005");
                enqueue(() -> read(gatt, sessionStateCharacteristic, "会话保活"));
            }
            handler.postDelayed(this, SESSION_KEEPALIVE_MS);
        }
    };
    private final Runnable startStatusFallback = () -> {
        if (finished) return;
        Log.w(TAG, "没有收到 WAP 状态指示，按参数齐全收尾");
        finishHandover();
    };

    private String cameraIp;
    private String cameraBssid;
    private long waitMillis = 800L;
    private String handoverSsid;
    private String handoverPassword;
    private boolean handoverAccepted;
    /** START 是否已成功写给相机。START 之前失败时相机不会被唤醒、热点也不会出现。 */
    private volatile boolean wifiStartRequested;

    private BroadcastReceiver bondReceiver;

    private final Runnable overallTimeout = () -> fail("蓝牙交接超时：相机没有回应");
    private final Runnable connectTimeout = () -> fail("连接相机蓝牙超时");

    public CanonBleHandover(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("手机蓝牙未开启");
            return;
        }
        status("正在扫描相机蓝牙…");
        handler.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS);
        // 已配对的相机优先直连：相机不再广播（例如刚被其它 App 连接过）时也能继续。
        if (tryBondedCamera(adapter)) return;
        BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException denied) {
            fail("缺少蓝牙扫描权限");
            return;
        }
        if (scanner == null) {
            fail("手机不支持蓝牙低功耗扫描");
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_SESSION))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException denied) {
            fail("缺少蓝牙扫描权限");
            return;
        }
        handler.postDelayed(() -> {
            stopScan(scanner);
            if (!finished && gatt == null) fail("没有发现相机蓝牙广播");
        }, SCAN_TIMEOUT_MS);
    }

    /**
     * 是否已把 START 写给相机。START 之前失败（蓝牙关闭/扫不到广播）相机根本不会开热点，
     * 回退入网可以很快放弃；START 之后失败则热点可能已在启动，需要给足等待时间。
     */
    public boolean isWifiStartRequested() {
        return wifiStartRequested;
    }

    /** 会话结束时释放 BLE 连接（不影响相机已经开着的 Wi‑Fi）。 */
    public void close() {
        finished = true;
        ready = false;
        watchdogArmed = false;
        handler.removeCallbacksAndMessages(null);
        unregisterBondReceiver();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    /** 已配对相机直连：名称像佳能机身的设备直接 connectGatt，跳过扫描。 */
    private boolean tryBondedCamera(BluetoothAdapter adapter) {
        java.util.Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            return false;
        }
        if (bonded == null) return false;
        for (BluetoothDevice device : bonded) {
            String name;
            try {
                name = device.getName();
            } catch (SecurityException denied) {
                continue;
            }
            if (name == null) continue;
            String upper = name.toUpperCase(Locale.ROOT);
            if (!upper.contains("EOS") && !upper.contains("CANON")) continue;
            Log.i(TAG, "使用已配对相机 " + name + " " + device.getAddress());
            if (connectTo(device)) return true;
        }
        return false;
    }

    /** 建立 GATT 连接；失败返回 false 让调用方回退扫描。 */
    private boolean connectTo(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException denied) {
            return false;
        }
        if (gatt == null) return false;
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        return true;
    }

    /** 静默关闭当前 GATT（配对期间断开等场景），不判失败。 */
    private void closeGattQuietly() {
        if (gatt == null) return;
        try {
            gatt.disconnect();
            gatt.close();
        } catch (Exception ignored) {
        }
        gatt = null;
    }

    // ── 扫描 ──

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (finished || gatt != null) return;
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            Log.i(TAG, "扫描命中 " + device.getAddress() + " name=" + device.getName()
                    + " rssi=" + result.getRssi());
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null) stopScan(adapter.getBluetoothLeScanner());
            status("发现相机，正在连接蓝牙…");
            if (!connectTo(device)) {
                fail("无法建立蓝牙连接");
            }
        }

        @Override public void onScanFailed(int errorCode) {
            fail("蓝牙扫描失败（错误码 " + errorCode + "）");
        }
    };

    private void stopScan(BluetoothLeScanner scanner) {
        if (scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
    }

    // ── GATT ──

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(
                BluetoothGatt gatt, int status, int newState) {
            if (finished) return;
            handler.removeCallbacks(connectTimeout);
            Log.i(TAG, "connection state status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("蓝牙已连接，正在发现服务…");
                try {
                    gatt.discoverServices();
                } catch (SecurityException denied) {
                    fail("缺少蓝牙连接权限");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (ready) {
                    // 交接完成后的 BLE 会话断开：只停保活，Wi‑Fi 会话由上层自行续命。
                    Log.w(TAG, "相机断开了蓝牙会话（status " + status + "）");
                    ready = false;
                    handler.removeCallbacks(sessionKeepAlive);
                    closeGattQuietly();
                    return;
                }
                if (bondReceiver != null) {
                    // 配对期间相机可能短暂断开：先不判失败，等 BOND_BONDED
                    // 回调后用已配对设备重连（见 registerBondReceiver）。
                    Log.i(TAG, "disconnected while bonding, waiting for bond result");
                    closeGattQuietly();
                    return;
                }
                fail("相机断开了蓝牙连接（status " + status + "）");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (finished) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("蓝牙服务发现失败（" + status + "）");
                return;
            }
            for (BluetoothGattService service : gatt.getServices()) {
                Log.i(TAG, "service " + service.getUuid());
            }
            Log.i(TAG, "services discovered, count=" + gatt.getServices().size());
            BluetoothGattService wap = gatt.getService(SERVICE_WAP);
            if (wap == null) {
                fail("相机不支持 Wi‑Fi 蓝牙交接");
                return;
            }
            BluetoothGattService session = gatt.getService(SERVICE_SESSION);
            if (session != null) {
                sessionInitCharacteristic = session.getCharacteristic(CHAR_SESSION_INIT);
                sessionStateCharacteristic = session.getCharacteristic(CHAR_SESSION_STATE);
                sessionIndCharacteristic = session.getCharacteristic(CHAR_SESSION_IND);
                sessionPairCharacteristic = session.getCharacteristic(CHAR_SESSION_PAIR);
                sessionCmdCharacteristic = session.getCharacteristic(CHAR_SESSION_CMD);
            }
            cpCharacteristic = wap.getCharacteristic(CHAR_WAP_CP);
            statusCharacteristic = wap.getCharacteristic(CHAR_WAP_STATUS);
            ssidCharacteristic = wap.getCharacteristic(CHAR_WAP_SSID);
            passwordCharacteristic = wap.getCharacteristic(CHAR_WAP_PASSWORD);
            apConfigCharacteristic = wap.getCharacteristic(CHAR_WAP_AP_CONFIG);
            securityCharacteristic = wap.getCharacteristic(CHAR_WAP_SECURITY);
            if (cpCharacteristic == null || ssidCharacteristic == null
                    || passwordCharacteristic == null) {
                fail("相机的蓝牙交接服务不完整");
                return;
            }
            BluetoothDevice device = gatt.getDevice();
            if (device == null) {
                fail("相机蓝牙设备不可用");
                return;
            }
            Log.i(TAG, "bondState=" + device.getBondState());
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                // P0：相机在接受 WAP 会话前通常要求先完成 BLE 配对（Just Works）。
                // 先配对，等 BOND_BONDED 后再开通知、写 START。
                Log.i(TAG, "creating bond before handover");
                registerBondReceiver(device);
                boolean started;
                try {
                    started = device.createBond();
                } catch (SecurityException denied) {
                    fail("缺少蓝牙连接权限，无法配对相机");
                    return;
                }
                if (!started) {
                    fail("无法发起与相机的蓝牙配对");
                    return;
                }
                return;
            }
            requestMtuThenSession(gatt);
        }

        @Override public void onDescriptorWrite(
                BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (finished) return;
            Log.i(TAG, "descriptor " + descriptor.getCharacteristic().getUuid()
                    + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                String uuid = descriptor.getCharacteristic().getUuid().toString();
                if (uuid.equals(CHAR_WAP_STATUS.toString())
                        || uuid.equals(CHAR_WAP_CP.toString())) {
                    fail("相机拒绝订阅蓝牙特征（" + status + "）");
                    return;
                }
                Log.w(TAG, "subscribe failed, continue: " + uuid + " status=" + status);
            }
            opDone();
        }

        @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "mtu=" + mtu + " status=" + status);
            if (!finished) {
                if (watchdogArmed) armSessionWatchdog();
                startSession(gatt);
            }
        }

        @Override public void onCharacteristicWrite(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (finished) return;
            Log.i(TAG, "write " + characteristic.getUuid() + " status=" + status);
            byte[] written = characteristic.getValue();
            int opcode = written != null && written.length > 0 ? (written[0] & 0xff) : -1;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 身份注册这类写失败只记日志；只有交接命令/会话确认失败才中止。
                if (CHAR_WAP_CP.equals(characteristic.getUuid())
                        || (CHAR_SESSION_CMD.equals(characteristic.getUuid()) && opcode == 1)) {
                    fail("相机拒绝了 Wi‑Fi 唤醒请求（" + status + "）");
                    return;
                }
                Log.w(TAG, "write failed, continue: " + characteristic.getUuid()
                        + " status=" + status);
                opDone();
                return;
            }
            if (CHAR_SESSION_CMD.equals(characteristic.getUuid()) && opcode == 1) {
                // 会话确认 {1} 已写入：按官方顺序紧接着发 START。
                enqueue(() -> startHandover(gatt));
            } else if (CHAR_WAP_CP.equals(characteristic.getUuid())) {
                status("相机已收到蓝牙交接命令（" + opcode + "）…");
            }
            opDone();
        }

        @Override public void onCharacteristicRead(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (finished) return;
            Log.i(TAG, "read " + characteristic.getUuid() + " status=" + status
                    + " value=" + hex(characteristic.getValue()));
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (CHAR_WAP_SSID.equals(characteristic.getUuid())
                        || CHAR_WAP_PASSWORD.equals(characteristic.getUuid())) {
                    fail("读取相机 Wi‑Fi 参数失败（" + status + "）");
                    return;
                }
                Log.w(TAG, "read failed, continue: " + characteristic.getUuid()
                        + " status=" + status);
                opDone();
                return;
            }
            byte[] value = characteristic.getValue();
            if (CHAR_WAP_SSID.equals(characteristic.getUuid())) {
                handoverSsid = ascii(characteristic);
            } else if (CHAR_WAP_PASSWORD.equals(characteristic.getUuid())) {
                handoverPassword = ascii(characteristic);
            } else if (CHAR_WAP_SECURITY.equals(characteristic.getUuid())) {
                securityTypes = parseSecurityTypes(value);
                Log.i(TAG, "security types=" + securityTypes);
            } else if (CHAR_WAP_AP_CONFIG.equals(characteristic.getUuid())) {
                // 官方 f3903t：START 是否支持携带安全类型看它的头 4 字节。
                apConfigData = value == null ? null : value.clone();
                Log.i(TAG, "ap config=" + hex(apConfigData));
            } else if (CHAR_SESSION_INIT.equals(characteristic.getUuid())) {
                // 官方按位判断还需要等哪些 0001000C 状态（机型名/电量/会话类型）。
                sessionFlags = value != null && value.length >= 4
                        ? ByteBuffer.wrap(value).getInt() : 0;
                Log.i(TAG, "session flags=0x" + Integer.toHexString(sessionFlags));
                maybeLogSessionReady();
            } else if (CHAR_SESSION_STATE.equals(characteristic.getUuid())) {
                Log.i(TAG, "session state=" + (value != null && value.length > 0
                        ? (value[0] & 0xff) : -1));
            }
            opDone();
        }

        @Override public void onCharacteristicChanged(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }

        @Override public void onCharacteristicChanged(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic, value);
        }
    };

    private void registerBondReceiver(BluetoothDevice device) {
        if (device == null || bondReceiver != null) return;
        bondReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice changed =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (changed == null || !changed.getAddress().equals(device.getAddress())) return;
                if (state == BluetoothDevice.BOND_BONDED) {
                    unregisterBondReceiver();
                    if (finished) return;
                    status("蓝牙配对完成，继续交接…");
                    if (gatt != null) {
                        requestMtuThenSession(gatt);
                    } else {
                        // 配对期间链路被相机断开：用已配对的设备重新连。
                        connectTo(changed);
                    }
                } else if (state == BluetoothDevice.BOND_NONE) {
                    fail("相机蓝牙配对失败");
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(bondReceiver, filter);
            }
        } catch (Exception ignored) {
            bondReceiver = null;
        }
    }

    private void unregisterBondReceiver() {
        BroadcastReceiver receiver = bondReceiver;
        bondReceiver = null;
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    /**
     * 拉大 MTU 后再走会话初始化：SSID/密码可能超过默认 23 字节。
     * 相机不回 MTU 时按超时兜底，不阻断流程。
     */
    private void requestMtuThenSession(BluetoothGatt gatt) {
        if (sessionStarted || finished) return;
        // 已配对设备（含本次刚配对完成）的会话初始化全程都该有进展，武装 20s 看门狗。
        BluetoothDevice device = gatt.getDevice();
        if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
            watchdogArmed = true;
            armSessionWatchdog();
        }
        try {
            if (gatt.requestMtu(512)) {
                handler.postDelayed(() -> {
                    if (!finished && !sessionStarted) startSession(gatt);
                }, MTU_TIMEOUT_MS);
                return;
            }
        } catch (Exception ignored) {
        }
        startSession(gatt);
    }

    /**
     * 官方初始化顺序（反编译 C0235o.B / C0203g / T.c）：
     * 未注册时走配对路径——写 {@code 00010006 = [1]+App名} → 等相机回 [2] →
     * 读 {@code 0001000B} → 订阅各服务 → 写 {@code 0001000A} 身份（UUID/昵称/类型）→
     * 发 REQUEST_AP_CONFIG → 读 SSID/密码/安全类型 → 会话确认 {1} → START。
     */
    private void startSession(BluetoothGatt gatt) {
        if (finished || sessionStarted) return;
        sessionStarted = true;
        Log.i(TAG, "startSession");
        if (sessionPairCharacteristic != null) {
            // 官方 startPairingSequence：先写 App 名并等相机回 [2]，确认后才读会话初始化。
            enqueue(() -> write(gatt, sessionPairCharacteristic, pairPayload(), "App 名注册"));
            enqueue(() -> enable(gatt, sessionPairCharacteristic, true));
            enqueue(this::awaitPairAck);
        }
        if (sessionInitCharacteristic != null) {
            enqueue(() -> read(gatt, sessionInitCharacteristic, "会话初始化"));
        }
        if (sessionStateCharacteristic != null) {
            enqueue(() -> read(gatt, sessionStateCharacteristic, "会话状态"));
            enqueue(() -> enable(gatt, sessionStateCharacteristic, false));
        }
        if (sessionIndCharacteristic != null) {
            enqueue(() -> enable(gatt, sessionIndCharacteristic, true));
        }
        if (apConfigCharacteristic != null) {
            enqueue(() -> read(gatt, apConfigCharacteristic, "AP 配置"));
        }
        if (statusCharacteristic != null) {
            // 官方对 00020003 用的是 indication；用 notification 会被相机拒绝（GATT 133 → Err 70）。
            enqueue(() -> enable(gatt, statusCharacteristic, true));
        }
        if (cpCharacteristic != null) {
            enqueue(() -> enable(gatt, cpCharacteristic, false));
        }
        subscribeAuxServices(gatt);
        // 官方在读 0001000B 成功后写 0001000A 的三条身份指令，再请求 AP 参数。
        enqueueIdentityWrites(gatt);
        if (cpCharacteristic != null) {
            // 官方先发 REQUEST_AP_CONFIG(0x0A)，等相机回 TLV 后才读 SSID/密码/安全类型。
            enqueue(() -> requestApConfig(gatt));
            enqueue(this::awaitApConfig);
        }
        if (ssidCharacteristic != null) {
            enqueue(() -> read(gatt, ssidCharacteristic, "SSID"));
        }
        if (passwordCharacteristic != null) {
            enqueue(() -> read(gatt, passwordCharacteristic, "密码"));
        }
        if (securityCharacteristic != null) {
            enqueue(() -> read(gatt, securityCharacteristic, "安全类型"));
        }
        // 参数齐了才写会话确认 {1}；写入成功后在回调里发 START。
        enqueue(() -> confirmAndStart(gatt));
    }

    /** {@code [1] + App 名称}：官方 startPairingSequence 的固定载荷。 */
    private byte[] pairPayload() {
        byte[] name = "IMAGE_FETCH".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[name.length + 1];
        payload[0] = 1;
        System.arraycopy(name, 0, payload, 1, name.length);
        return payload;
    }

    /**
     * 官方在会话初始化后向 {@code 0001000A} 连写三条身份指令：
     * {@code {3}+16 字节 UUID}、{@code {4}+手机昵称}、{@code {5,2}}。
     * 少写这三条，相机可能仍接受 START 却不真正打开 AP。
     */
    private void enqueueIdentityWrites(BluetoothGatt gatt) {
        if (sessionCmdCharacteristic == null) return;
        byte[] uuid = appUuidBytes();
        if (uuid != null) {
            byte[] payload = new byte[17];
            payload[0] = 3;
            System.arraycopy(uuid, 0, payload, 1, 16);
            enqueue(() -> write(gatt, sessionCmdCharacteristic, payload, "UUID 注册"));
        }
        String nickname = deviceNickname();
        byte[] name = nickname.getBytes(StandardCharsets.US_ASCII);
        byte[] namePayload = new byte[name.length + 1];
        namePayload[0] = 4;
        System.arraycopy(name, 0, namePayload, 1, name.length);
        enqueue(() -> write(gatt, sessionCmdCharacteristic, namePayload, "昵称注册"));
        enqueue(() -> write(gatt, sessionCmdCharacteristic, new byte[]{5, 2}, "会话类型"));
        // 官方 J(true)：仅当 0001000B 能力位 64 置位时才允许发「禁止自动关机」，
        // 否则相机会回 GATT 133 并直接断开蓝牙。
        enqueue(() -> {
            if ((sessionFlags & 64) == 64) {
                write(gatt, sessionCmdCharacteristic, new byte[]{10, 1}, "禁止自动关机");
            } else {
                Log.i(TAG, "相机不支持禁止自动关机（flags=0x" + Integer.toHexString(sessionFlags) + "）");
                opDone();
            }
        });
    }

    /** 复用 PTP/IP 的发起方 GUID（官方 BLE 与 PTP 用的是同一个 UUID）。 */
    private byte[] appUuidBytes() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "image_fetch_prefs", Context.MODE_PRIVATE);
            String text = prefs.getString("ptpip_initiator_guid", "");
            if (text == null || text.length() != 32) return null;
            byte[] uuid = new byte[16];
            for (int i = 0; i < 16; i++) {
                uuid[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
            }
            return uuid;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 官方写入的手机昵称；读不到蓝牙名时退回 App 名。 */
    private String deviceNickname() {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            String name = adapter == null ? null : adapter.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Exception ignored) {
        }
        return "IMAGE_FETCH";
    }

    /** 等相机对 App 名注册回 [2]；没回执就按超时继续，不让流程卡死。 */
    private void awaitPairAck() {
        if (finished) return;
        if (pairAckSeen) {
            opDone();
            return;
        }
        pairWaitActive = true;
        handler.postDelayed(() -> {
            if (!pairWaitActive) return;
            pairWaitActive = false;
            Log.w(TAG, "App 名注册无回执，继续会话初始化");
            opDone();
        }, PAIR_ACK_TIMEOUT_MS);
    }

    /** 等相机对 REQUEST_AP_CONFIG 回 TLV（00020002）；超时也继续读，避免整条流程卡住。 */
    private void awaitApConfig() {
        if (finished) return;
        if (apConfigSeen) {
            opDone();
            return;
        }
        apWaitActive = true;
        handler.postDelayed(() -> {
            if (!apWaitActive) return;
            apWaitActive = false;
            Log.w(TAG, "AP 配置回执超时，继续读取 SSID/密码");
            opDone();
        }, AP_CONFIG_TIMEOUT_MS);
    }

    /**
     * 官方 B.a()：IP/BSSID/SSID/密码/安全类型五项齐了，才写 {@code 0001000A {1}}
     * 表示交接就绪；写入成功后在 onCharacteristicWrite 里发 START。
     */
    private void confirmAndStart(BluetoothGatt gatt) {
        if (finished) return;
        if (cameraIp == null || handoverSsid == null || handoverPassword == null
                || securityTypes == null) {
            fail("相机没有返回完整的 Wi‑Fi 参数");
            return;
        }
        if (sessionCmdCharacteristic == null) {
            startHandover(gatt);
            return;
        }
        status("相机 Wi‑Fi 参数就绪，正在启动热点…");
        write(gatt, sessionCmdCharacteristic, new byte[]{1}, "会话确认");
    }

    /** 官方 b()：00020001 的头 4 字节含 16 且安全类型列表包含 9，才发带安全类型的 START。 */
    private boolean supportsSecurityTypeStart() {
        if (apConfigData == null || apConfigData.length < 4 || securityTypes == null) return false;
        int capabilities = ByteBuffer.wrap(apConfigData).getInt();
        return (capabilities & 16) == 16 && securityTypes.contains(9);
    }

    /** 官方的其余订阅：遥控(0003)、实时取景(0004)、相机状态(0008)。少一个相机都可能不认 START。 */
    private void subscribeAuxServices(BluetoothGatt gatt) {
        BluetoothGattService rc = gatt.getService(SERVICE_RC);
        if (rc != null) {
            enqueueRead(gatt, rc, "00030001-0000-1000-0000-d8492fffa821", "遥控状态");
            enqueueEnable(gatt, rc, "00030001-0000-1000-0000-d8492fffa821", false);
            enqueueEnable(gatt, rc, "00030002-0000-1000-0000-d8492fffa821", false);
            enqueueEnable(gatt, rc, "00030011-0000-1000-0000-d8492fffa821", false);
            enqueueEnable(gatt, rc, "00030021-0000-1000-0000-d8492fffa821", false);
            enqueueEnable(gatt, rc, "00030031-0000-1000-0000-d8492fffa821", false);
        }
        BluetoothGattService live = gatt.getService(SERVICE_LIVE);
        if (live != null) {
            enqueueRead(gatt, live, "00040001-0000-1000-0000-d8492fffa821", "取景状态");
            enqueueRead(gatt, live, "00040003-0000-1000-0000-d8492fffa821", "取景信息");
            enqueueEnable(gatt, live, "00040003-0000-1000-0000-d8492fffa821", true);
        }
        BluetoothGattService status = gatt.getService(SERVICE_STATUS);
        if (status != null) {
            enqueueRead(gatt, status, "00080001-0000-1000-0000-d8492fffa821", "机身状态");
            enqueueEnable(gatt, status, "00080003-0000-1000-0000-d8492fffa821", true);
            BluetoothGattCharacteristic call = status.getCharacteristic(
                    UUID.fromString("00080002-0000-1000-0000-d8492fffa821"));
            if (call != null) {
                enqueue(() -> write(gatt, call, new byte[]{1}, "状态订阅"));
            }
        }
    }

    private void enqueueRead(
            BluetoothGatt gatt, BluetoothGattService service, String uuid, String label) {
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuid));
        if (characteristic != null) enqueue(() -> read(gatt, characteristic, label));
    }

    private void enqueueEnable(
            BluetoothGatt gatt, BluetoothGattService service, String uuid, boolean indication) {
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(uuid));
        if (characteristic != null) enqueue(() -> enable(gatt, characteristic, indication));
    }

    private void write(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, String label) {
        try {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(value);
            boolean ok = gatt.writeCharacteristic(characteristic);
            Log.i(TAG, "write " + label + " ok=" + ok);
            if (!ok) opDone();
        } catch (Exception error) {
            Log.w(TAG, "write failed " + label, error);
            opDone();
        }
    }

    private void enqueue(Runnable op) {
        gattOps.add(op);
        runNextOp();
    }

    private void runNextOp() {
        if (finished || opInFlight) return;
        Runnable op = gattOps.poll();
        if (op == null) return;
        opInFlight = true;
        op.run();
    }

    private void opDone() {
        opInFlight = false;
        // 每完成一步 GATT 读写都算进展，看门狗重新计时。
        if (watchdogArmed) armSessionWatchdog();
        runNextOp();
    }

    /** 已配对设备的会话初始化：20s 没有任何进展就判相机蓝牙卡死，不再陪跑到 90s。 */
    private void armSessionWatchdog() {
        handler.removeCallbacks(sessionProgressTimeout);
        if (!finished && !ready) {
            handler.postDelayed(sessionProgressTimeout, SESSION_PROGRESS_TIMEOUT_MS);
        }
    }

    private void disarmSessionWatchdog() {
        watchdogArmed = false;
        handler.removeCallbacks(sessionProgressTimeout);
    }

    private void read(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, String label) {
        try {
            boolean ok = gatt.readCharacteristic(characteristic);
            Log.i(TAG, "read request " + label + " ok=" + ok);
            if (!ok) opDone();
        } catch (Exception error) {
            Log.w(TAG, "read request failed: " + label, error);
            opDone();
        }
    }

    private void enable(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean indication) {
        try {
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) {
                opDone();
                return;
            }
            descriptor.setValue(indication
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(descriptor);
            Log.i(TAG, "enable " + characteristic.getUuid() + " indication=" + indication + " ok=" + ok);
            if (!ok) opDone();
        } catch (Exception error) {
            Log.w(TAG, "enable failed " + characteristic.getUuid(), error);
            opDone();
        }
    }

    /** 请求相机生成 AP 参数（SSID/密码/安全类型）。写成功由 onCharacteristicWrite 放行队列。 */
    private void requestApConfig(BluetoothGatt gatt) {
        if (finished || cpCharacteristic == null) {
            opDone();
            return;
        }
        try {
            cpCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cpCharacteristic.setValue(new byte[]{10});
            boolean ok = gatt.writeCharacteristic(cpCharacteristic);
            Log.i(TAG, "写 REQUEST_AP_CONFIG(0x0A) ok=" + ok);
            if (!ok) opDone();
        } catch (Exception error) {
            Log.w(TAG, "写 REQUEST_AP_CONFIG 异常", error);
            opDone();
        }
    }

    /** 写 START：相机收到后打开 Wi‑Fi，并把 SSID/密码/IP 通过通知与读取交回。 */
    private void startHandover(BluetoothGatt gatt) {
        if (finished || handoverAccepted) return;
        handoverAccepted = true;
        if (cpCharacteristic == null) {
            fail("相机的蓝牙交接通道不可用");
            return;
        }
        // 官方 b()：能力位含 16 且安全类型支持 9 才发 START_WITH_SECURITY_TYPE，否则发普通 START。
        boolean secure = supportsSecurityTypeStart();
        byte[] payload = secure ? new byte[]{4, 0, 0, 0, 9} : new byte[]{1};
        try {
            cpCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cpCharacteristic.setValue(payload);
            boolean ok = gatt.writeCharacteristic(cpCharacteristic);
            Log.i(TAG, "写 START(" + (secure ? "4,9" : "1") + ") 到 WAP CP ok=" + ok);
            if (ok) {
                wifiStartRequested = true;
                handler.postDelayed(startStatusFallback, START_STATUS_TIMEOUT_MS);
            } else {
                handoverAccepted = false;
                opDone();
            }
        } catch (Exception error) {
            Log.w(TAG, "写 START 异常", error);
            handoverAccepted = false;
            opDone();
        }
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (finished || value == null || value.length == 0) return;
        // 收到相机任何通知都算进展，看门狗重新计时。
        if (watchdogArmed) armSessionWatchdog();
        Log.i(TAG, "notify " + characteristic.getUuid() + " " + hex(value));
        if (CHAR_SESSION_PAIR.equals(characteristic.getUuid())) {
            // 官方：相机回 [2] 表示 App 名注册完成，之后才读 0001000B。
            if ((value[0] & 0xff) == 2) {
                pairAckSeen = true;
                if (pairWaitActive) {
                    pairWaitActive = false;
                    opDone();
                }
            }
            return;
        }
        if (CHAR_SESSION_IND.equals(characteristic.getUuid())) {
            handleSessionIndication(value);
            return;
        }
        if (CHAR_WAP_STATUS.equals(characteristic.getUuid())) {
            if (value.length < 2) return;
            int code = value[0] & 0xff;
            int subCode = value[1] & 0xff;
            // 官方：Code=1 才是 WAP 状态；SubCode 3=开始成功，4/5=被拒绝。
            if (code == 1 && subCode == 3) {
                handler.removeCallbacks(startStatusFallback);
                status("相机已开始开启 Wi‑Fi…");
                finishHandover();
            } else if (code == 1 && (subCode == 4 || subCode == 5)) {
                fail("相机拒绝了 Wi‑Fi 唤醒请求");
            }
            return;
        }
        if (!CHAR_WAP_CP.equals(characteristic.getUuid())) return;
        // 相机回包前 2 字节是头（实测 0x7F 0x10），后面才是 TLV；IP/BSSID 为小端序。
        parseTlv(value, 2);
        apConfigSeen = true;
        if (apWaitActive) {
            apWaitActive = false;
            opDone();
        }
    }

    /**
     * 官方对 0001000C 指示的解析：
     * 1/2 = 机型名两段，3 = 电量，4 = 错误码，5 = 设备信息列表，7 = 会话类型/状态。
     */
    private void handleSessionIndication(byte[] value) {
        int type = value[0] & 0xff;
        if (type == 1) {
            modelName1 = asciiFrom(value, 1);
        } else if (type == 2) {
            modelName2 = asciiFrom(value, 1);
        } else if (type == 3) {
            if (value.length > 1) batteryLevel = value[1] & 0xff;
        } else if (type == 4) {
            if (value.length >= 5) {
                int code = ByteBuffer.wrap(value, 1, 4).getInt();
                if (code != 1) status("相机状态异常（" + code + "）");
            }
        } else if (type == 5) {
            Log.i(TAG, "device info list bytes=" + value.length);
        } else if (type == 7) {
            if (value.length > 1) cameraState = value[1] & 0xff;
        }
        maybeLogSessionReady();
    }

    /** 官方 T.a()：0001000B 要求的机型名/电量/会话状态都到齐后，会话才算就绪。 */
    private void maybeLogSessionReady() {
        if (sessionReadyLogged) return;
        if ((sessionFlags & 8) != 0 && (modelName1 == null || modelName2 == null)) return;
        if ((sessionFlags & 16) != 0 && batteryLevel < 0) return;
        if ((sessionFlags & 512) != 0 && cameraState < 0) return;
        sessionReadyLogged = true;
        String model = ((modelName1 == null ? "" : modelName1)
                + (modelName2 == null ? "" : modelName2)).trim();
        Log.i(TAG, "session ready model=" + model + " battery=" + batteryLevel
                + " state=" + cameraState);
        if (!model.isEmpty()) status("相机：" + model);
    }

    private static java.util.List<Integer> parseSecurityTypes(byte[] value) {
        if (value == null || value.length < 4) return null;
        int count = value.length / 4;
        java.util.List<Integer> types = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            types.add(ByteBuffer.wrap(value, i * 4, 4).getInt());
        }
        return types;
    }

    private static String asciiFrom(byte[] value, int from) {
        if (value == null || value.length <= from) return "";
        String text = new String(value, from, value.length - from, StandardCharsets.US_ASCII);
        int end = text.indexOf('\0');
        if (end >= 0) text = text.substring(0, end);
        return text.trim();
    }

    /** TLV：type、length、payload 连续排列。 */
    private void parseTlv(byte[] data, int offset) {
        int index = offset;
        while (index + 1 < data.length) {
            int type = data[index] & 0xff;
            int length = data[index + 1] & 0xff;
            int start = index + 2;
            int end = start + length;
            if (end > data.length) break;
            if (type == 16 && length >= 4) {
                // 相机按小端序回传：C0 A8 01 02 → 192.168.1.2
                cameraIp = (data[start + 3] & 0xff) + "." + (data[start + 2] & 0xff) + "."
                        + (data[start + 1] & 0xff) + "." + (data[start] & 0xff);
            } else if (type == 2 && length >= 6) {
                StringBuilder mac = new StringBuilder();
                for (int i = 5; i >= 0; i--) {
                    if (i < 5) mac.append(':');
                    mac.append(String.format(Locale.ROOT, "%02X", data[start + i] & 0xff));
                }
                cameraBssid = mac.toString();
            } else if (type == 48 && length >= 1) {
                waitMillis = Math.max(600L, (data[start] & 0xff) * 100L);
            }
            index = end;
        }
    }

    private void finishHandover() {
        if (finished || ready) return;
        if (cameraIp == null) {
            fail("相机没有返回 Wi‑Fi 地址");
            return;
        }
        Log.i(TAG, "handover OK ssid=" + handoverSsid + " ip=" + cameraIp
                + " bssid=" + cameraBssid + " wait=" + waitMillis);
        ready = true;
        handler.removeCallbacks(overallTimeout);
        disarmSessionWatchdog();
        unregisterBondReceiver();
        status("蓝牙交接完成：" + cameraIp);
        callback.onReady(new Result(handoverSsid, handoverPassword, cameraIp,
                cameraBssid, waitMillis));
        // 会话期间保持 BLE 有心跳：官方在 Wi‑Fi 连接期间 BLE 也一直在线。
        handler.postDelayed(sessionKeepAlive, SESSION_KEEPALIVE_MS);
    }

    private static String ascii(BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        if (value == null) return "";
        String text = new String(value, StandardCharsets.US_ASCII);
        int end = text.indexOf('\0');
        if (end >= 0) text = text.substring(0, end);
        return text.trim();
    }

    private static String hex(byte[] value) {
        if (value == null) return "null";
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) {
            text.append(String.format(Locale.ROOT, "%02X", item & 0xff));
        }
        return text.toString();
    }

    private void status(String message) {
        Log.i(TAG, message);
        if (!finished) handler.post(() -> callback.onStatus(message));
    }

    private void fail(String message) {
        if (finished || ready) return;
        Log.w(TAG, "FAIL: " + message);
        finished = true;
        ready = false;
        disarmSessionWatchdog();
        handler.removeCallbacksAndMessages(null);
        unregisterBondReceiver();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        callback.onFailed(message);
    }
}
