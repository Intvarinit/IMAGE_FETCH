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
    private static final UUID SERVICE_WAP =
            UUID.fromString("00020000-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_CP =
            UUID.fromString("00020002-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_STATUS =
            UUID.fromString("00020003-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_SSID =
            UUID.fromString("00020004-0000-1000-0000-d8492fffa821");
    private static final UUID CHAR_WAP_PASSWORD =
            UUID.fromString("00020005-0000-1000-0000-d8492fffa821");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 10_000L;
    private static final long OVERALL_TIMEOUT_MS = 35_000L;
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

    private String cameraIp;
    private String cameraBssid;
    private long waitMillis = 800L;
    private String handoverSsid;
    private String handoverPassword;
    private boolean handoverAccepted;

    private boolean finished;
    private int pendingDescriptorWrites;
    private boolean indicationRetried;
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
            cpCharacteristic = wap.getCharacteristic(CHAR_WAP_CP);
            statusCharacteristic = wap.getCharacteristic(CHAR_WAP_STATUS);
            ssidCharacteristic = wap.getCharacteristic(CHAR_WAP_SSID);
            passwordCharacteristic = wap.getCharacteristic(CHAR_WAP_PASSWORD);
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
            // 官方实现只在特定条件下才 createBond；多数机型直接进入会话/交接即可。
            // 强制配对会让相机断开链路，所以这里先不配对，写失败再回退。
            enableNotifications(gatt);
        }

        @Override public void onDescriptorWrite(
                BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (finished) return;
            Log.i(TAG, "descriptor " + descriptor.getCharacteristic().getUuid()
                    + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS && !indicationRetried) {
                // 有些机型只支持 indication：换一种描述符值再试一次。
                indicationRetried = true;
                try {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    return;
                } catch (Exception ignored) {
                }
            }
            pendingDescriptorWrites--;
            if (pendingDescriptorWrites > 0) return;
            // 通知开好后先尝试拉大 MTU：SSID/密码读取可能超过默认 23 字节。
            try {
                if (!gatt.requestMtu(512)) startHandover(gatt);
            } catch (Exception ignored) {
                startHandover(gatt);
            }
            handler.postDelayed(() -> {
                if (!finished && !handoverAccepted && cameraIp == null) startHandover(gatt);
            }, MTU_TIMEOUT_MS);
        }

        @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (!finished) startHandover(gatt);
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
                status("相机已收到唤醒请求，正在打开 Wi‑Fi…");
            }
        }

        @Override public void onCharacteristicRead(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (finished) return;
            Log.i(TAG, "read " + characteristic.getUuid() + " status=" + status
                    + " value=" + hex(characteristic.getValue()));
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (CHAR_WAP_SSID.equals(characteristic.getUuid())) {
                    fail("读取相机 Wi‑Fi 名称失败（" + status + "）");
                }
                return;
            }
            if (CHAR_WAP_SSID.equals(characteristic.getUuid())) {
                handoverSsid = ascii(characteristic);
                readPassword(gatt);
            } else if (CHAR_WAP_PASSWORD.equals(characteristic.getUuid())) {
                handoverPassword = ascii(characteristic);
                finishHandover();
            }
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
                    if (!finished && gatt != null) enableNotifications(gatt);
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

    private void enableNotifications(BluetoothGatt gatt) {
        pendingDescriptorWrites = 0;
        if (statusCharacteristic != null && enableNotification(gatt, statusCharacteristic)) {
            pendingDescriptorWrites++;
        }
        if (cpCharacteristic != null && enableNotification(gatt, cpCharacteristic)) {
            pendingDescriptorWrites++;
        }
        if (pendingDescriptorWrites == 0) startHandover(gatt);
    }

    private boolean enableNotification(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) return false;
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return gatt.writeDescriptor(descriptor);
        } catch (Exception ignored) {
            return false;
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
            cpCharacteristic.setValue(new byte[]{1});
            boolean ok = gatt.writeCharacteristic(cpCharacteristic);
            Log.i(TAG, "写 START 到 WAP CP ok=" + ok);
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
        if ((value[0] & 0xff) != 16) return; // 0x10 = WAP CP Response
        parseTlv(value, 2);
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
                cameraIp = (data[start] & 0xff) + "." + (data[start + 1] & 0xff) + "."
                        + (data[start + 2] & 0xff) + "." + (data[start + 3] & 0xff);
            } else if (type == 2 && length >= 6) {
                StringBuilder mac = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    if (i > 0) mac.append(':');
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

    private void readPassword(BluetoothGatt gatt) {
        if (passwordCharacteristic == null) return;
        try {
            gatt.readCharacteristic(passwordCharacteristic);
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
