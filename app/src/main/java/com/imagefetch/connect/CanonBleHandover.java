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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

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
    /** 首次要等系统配对弹窗由用户确认，整体窗口放宽到 90s。 */
    private static final long OVERALL_TIMEOUT_MS = 90_000L;
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

    private String cameraIp;
    private String cameraBssid;
    private long waitMillis = 800L;
    private String handoverSsid;
    private String handoverPassword;
    private boolean handoverAccepted;

    private boolean finished;
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

    /** 会话结束时释放 BLE 连接（不影响相机已经开着的 Wi‑Fi）。 */
    public void close() {
        finished = true;
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
            if (!finished) startSession(gatt);
        }

        @Override public void onCharacteristicWrite(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (finished) return;
            Log.i(TAG, "write " + characteristic.getUuid() + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("相机拒绝了 Wi‑Fi 唤醒请求（" + status + "）");
                return;
            }
            if (CHAR_WAP_CP.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                int opcode = value != null && value.length > 0 ? (value[0] & 0xff) : 0;
                status("相机已收到蓝牙交接命令（" + opcode + "）…");
                // START 已被相机接受：参数（IP/SSID/密码）在 {10} 的回包与后续读取里拿齐了。
                if ((opcode == 1 || opcode == 4) && cameraIp != null
                        && handoverSsid != null && handoverPassword != null) {
                    finishHandover();
                }
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
            if (CHAR_WAP_SSID.equals(characteristic.getUuid())) {
                handoverSsid = ascii(characteristic);
            } else if (CHAR_WAP_PASSWORD.equals(characteristic.getUuid())) {
                handoverPassword = ascii(characteristic);
            } else if (CHAR_WAP_SECURITY.equals(characteristic.getUuid())) {
                Log.i(TAG, "security type=" + hex(characteristic.getValue()));
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
     * 官方初始化顺序（来自 Camera Connect 反编译）：
     * 读会话初始化 0001000B → 读 00010005 并开通知 → 开 0001000C 指示 →
     * 读 00020001 → 00020003 用 indication、00020002 用 notification →
     * 读 SSID/密码/安全类型 → 最后写 START。
     */
    private void startSession(BluetoothGatt gatt) {
        if (finished || sessionStarted) return;
        sessionStarted = true;
        Log.i(TAG, "startSession");
        if (sessionInitCharacteristic != null) {
            enqueue(() -> read(gatt, sessionInitCharacteristic, "会话初始化"));
        }
        if (sessionPairCharacteristic != null) {
            // 官方 startPairingSequence：向 00010006 写 [1] + App 名称。
            // 相机里虽然已有 IMAGE_FETCH，但官方每次建会话仍会重写一次。
            byte[] name = "IMAGE_FETCH".getBytes(StandardCharsets.US_ASCII);
            byte[] payload = new byte[name.length + 1];
            payload[0] = 1;
            System.arraycopy(name, 0, payload, 1, name.length);
            enqueue(() -> write(gatt, sessionPairCharacteristic, payload, "App 名注册"));
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
        if (cpCharacteristic != null) {
            // 官方在 START 之前先发 REQUEST_AP_CONFIG(0x0A)，让相机生成 SSID/密码/安全类型；
            // 直接读会拿到全 0（实测）。
            enqueue(() -> requestApConfig(gatt));
            enqueue(() -> handler.postDelayed(this::opDone, 500L));
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
        if (sessionCmdCharacteristic != null) {
            // 官方 T.c(Q.SUCCESS)：交接就绪后先写会话确认 {1} 到 0001000A。
            enqueue(() -> write(gatt, sessionCmdCharacteristic, new byte[]{1}, "会话确认"));
        }
        enqueue(() -> startHandover(gatt));
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
        runNextOp();
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
        try {
            cpCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            // 官方支持 START_WITH_SECURITY_TYPE(0x04)+int：安全类型 9（WPA2）。
            // 之前只发 START(0x01)，相机回 GATT 0 却不开 AP，改发带安全类型的命令。
            cpCharacteristic.setValue(new byte[]{4, 0, 0, 0, 9});
            boolean ok = gatt.writeCharacteristic(cpCharacteristic);
            Log.i(TAG, "写 START_WITH_SECURITY_TYPE(4,9) 到 WAP CP ok=" + ok);
            if (!ok) handoverAccepted = false;
        } catch (Exception error) {
            Log.w(TAG, "写 START 异常", error);
            handoverAccepted = false;
        }
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (finished || value == null || value.length == 0) return;
        Log.i(TAG, "notify " + characteristic.getUuid() + " " + hex(value));
        if (CHAR_WAP_STATUS.equals(characteristic.getUuid())) {
            if (value.length < 2) return;
            int subCode = value[1] & 0xff;
            if (subCode == 3) {
                status("相机已开始开启 Wi‑Fi…");
            } else if (subCode == 4 || subCode == 5) {
                fail("相机拒绝了 Wi‑Fi 唤醒请求");
            }
            return;
        }
        if (!CHAR_WAP_CP.equals(characteristic.getUuid())) return;
        // 相机回包前 2 字节是头（实测 0x7F 0x10），后面才是 TLV；IP/BSSID 为小端序。
        parseTlv(value, 2);
        if (cameraIp != null && handoverSsid != null && handoverPassword != null) {
            finishHandover();
        }
        if (cameraIp != null && handoverSsid == null) {
            readSsid(gatt);
        }
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

    private void readSsid(BluetoothGatt gatt) {
        if (ssidCharacteristic == null) return;
        try {
            gatt.readCharacteristic(ssidCharacteristic);
        } catch (Exception ignored) {
        }
    }

    private void finishHandover() {
        if (finished) return;
        if (cameraIp == null) {
            fail("相机没有返回 Wi‑Fi 地址");
            return;
        }
        Log.i(TAG, "handover OK ssid=" + handoverSsid + " ip=" + cameraIp
                + " bssid=" + cameraBssid + " wait=" + waitMillis);
        finished = true;
        handler.removeCallbacksAndMessages(null);
        unregisterBondReceiver();
        status("蓝牙交接完成：" + cameraIp);
        callback.onReady(new Result(handoverSsid, handoverPassword, cameraIp,
                cameraBssid, waitMillis));
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
        if (finished) return;
        Log.w(TAG, "FAIL: " + message);
        finished = true;
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
