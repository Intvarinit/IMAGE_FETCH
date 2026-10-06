/*
 * Wi‑Fi 网络绑定、心跳保活与指数退避重连逻辑移植自 ZENCHE
 * (https://github.com/Tauber01/ZENCHE) native/android MainActivity.java，
 * MIT License, Copyright (c) 2026 Tauber01. See the NOTICE file.
 */
package com.imagefetch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.PtpOperationService;
import com.imagefetch.storage.GalleryExporter;
import com.imagefetch.storage.PhotoRepository;
import com.imagefetch.transfer.HeartbeatMonitor;
import com.imagefetch.transfer.NewPhotoResolver;
import com.imagefetch.transfer.PhotoTransferWorker;
import com.imagefetch.transfer.ReconnectPolicy;
import com.imagefetch.transfer.TransferControl;
import com.imagefetch.transport.CameraTransport;
import com.imagefetch.transport.PtpIpTransport;
import com.imagefetch.transport.UsbPtpTransport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话编排：连接（含相机 Wi‑Fi 精确路由）→ 拍摄 → 发现新对象 → 流式取回 → 落盘去重；
 * 并负责 event 通道心跳保活与连续失败后的指数退避自动重连。
 * 所有相机 IO 都在单线程 executor 上串行执行，UI 回调切回主线程。
 */
public final class CameraSessionCoordinator {
    /** 传输方式：Wi‑Fi/PTP‑IP 或 USB Host/PTP。 */
    public enum TransportKind {
        WIFI,
        USB
    }

    public interface Listener {
        void onStatus(String message);

        void onConnected(String cameraName);

        /** 链路丢失、自动重连进行中（attempt 从 1 开始，delayMillis 为本次退避）。 */
        void onReconnecting(int attempt, long delayMillis);

        void onDisconnected();

        void onProgress(long received, long total);

        /** note 为相册导出结果（含失败原因）；界面上必须与文件名一起显示，否则会被覆盖掉。 */
        void onFileSaved(File file, long bytes, long elapsedMillis, String note);

        /** 一批下载结束（含失败/取消）：界面据此清掉图片栏里已下载图片的选中状态。 */
        void onDownloadFinished();

        void onError(String message, Throwable error);
    }

    /** 拍后等待“写卡完成 + 新对象可读”的上限。 */
    private static final long CAPTURE_DISCOVERY_TIMEOUT_MS = 20000L;
    private static final long CAPTURE_POLL_INTERVAL_MS = 1000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PtpIpTransport wifiTransport = new PtpIpTransport();
    private final UsbPtpTransport usbTransport;
    /** 当前会话的传输实现；连接前为 null。 */
    private volatile CameraTransport transport;
    private volatile PtpOperationService operations;
    private final PhotoRepository repository;
    private final GalleryExporter galleryExporter;
    /** 落盘后是否再导出到系统相册（默认开；关闭时只保留应用内副本）。 */
    private volatile boolean exportToGallery = true;
    /** 每页张数（快速分页与完整扫描共用）；由设置界面调整。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;
    /** 放大预览的原图解码目标边长（像素）。 */
    private static final int PREVIEW_MAX_PX = 1600;
    /** 拍摄前基线只需覆盖最新的若干张，不必整卡枚举。 */
    private static final int CAPTURE_BASELINE_ITEMS = 60;
    /** 相机不回 PONG 时的保活周期：真实命令往返比 PING 重，放慢到 30s。 */
    private static final long COMMAND_PROBE_INTERVAL_MS = 30000L;
    /** 传输结束后的冷却期：相机可能仍在写卡，期间不做保活判定。 */
    private static final long TRANSFER_COOLDOWN_MS = 10000L;
    /** 最近一次传输结束时刻（含失败），用于心跳冷却。 */
    private volatile long lastTransferFinishedAt;
    /** 完整扫描结果缓存：同一会话内翻页复用，避免每页重扫整卡。 */
    private volatile List<CameraStorage.Item> fullScanItems;
    private volatile long thumbnailGeneration;
    private volatile long previewGeneration;
    private volatile int pageSize = DEFAULT_PAGE_SIZE;

    /** 缩略图缓存：按张数计，避免来回滚动时重复向相机取图。 */
    private final LruCache<Long, byte[]> thumbnailCache =
            new LruCache<Long, byte[]>(64) {
                @Override protected int sizeOf(Long key, byte[] value) {
                    return 1;
                }
            };

    /** 原图预览缓存：按字节数计（一张解码后的原图可达数 MB）。 */
    private final LruCache<Long, Bitmap> previewCache;
    private final File previewCacheDir;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiNetworkCallback;
    private Network cameraWifiNetwork;
    private Network sessionNetwork;
    /** 会话期间持有：避免息屏/待机时 Wi‑Fi 无线电休眠导致传输中断。 */
    private final WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;

    private volatile long connectionGeneration;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean reconnecting;
    private volatile boolean manualDisconnect;
    private final HeartbeatMonitor heartbeat =
            new HeartbeatMonitor(ReconnectPolicy.OFFLINE_THRESHOLD);
    private volatile int reconnectAttempt;
    private volatile String cameraName = "";
    private volatile String cameraHost = "";
    private volatile int cameraPort = 15740;

    private volatile NewPhotoResolver activeResolver;
    private volatile TransferControl control;
    /** 相册导出失败是否已提示过（每次拍摄/下载开始时复位）。 */
    private volatile boolean exportFailureReported;
    /** 最近一次手动断开时相机侧会话的释放结果（诊断用，会随下一次连接失败一起展示）。 */
    private volatile String lastSessionRelease = "";

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            if (!connected || reconnecting || manualDisconnect) return;
            final CameraTransport active = transport;
            if (active == null || !active.supportsHeartbeat()) return;
            if (!(active instanceof PtpIpTransport)) return;
            final PtpIpTransport ptpIp = (PtpIpTransport) active;
            // 拍摄/传输进行中，以及刚传完的冷却期：本 tick 跳过，避免保活探测与会话事务相互等待
            // （相机写完卡前响应变慢属正常，此时判活容易误报离线）。
            if (control != null
                    || System.currentTimeMillis() - lastTransferFinishedAt
                            < TRANSFER_COOLDOWN_MS) {
                startHeartbeat();
                return;
            }
            final long heartbeatGeneration = connectionGeneration;
            executor.submit(() -> {
                boolean ok = false;
                try {
                    ptpIp.probe();
                    ok = true;
                } catch (Exception ignored) {
                    // 心跳失败：计入连续失败计数。
                }
                final boolean alive = ok;
                mainHandler.post(() -> {
                    if (!connected || manualDisconnect
                            || heartbeatGeneration != connectionGeneration) return;
                    if (heartbeat.recordProbeResult(alive)) {
                        enterReconnecting();
                        return;
                    }
                    // 相机不回 PONG 时走真实命令探测，代价高，周期放长。
                    startHeartbeat(ptpIp.pingKeepAliveSupported()
                            ? ReconnectPolicy.HEARTBEAT_INTERVAL_MS
                            : COMMAND_PROBE_INTERVAL_MS);
                });
            });
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!reconnecting || manualDisconnect) return;
            connecting = true;
            final long generation = ++connectionGeneration;
            registerWifiNetworkCallback();
            executor.submit(() -> {
                try {
                    CameraTransport active = transport;
                    if (active instanceof PtpIpTransport) {
                        seedCameraWifiNetwork();
                        Network network = liveCameraWifiNetwork();
                        sessionNetwork = network;
                        ((PtpIpTransport) active).setSocketFactory(
                                network != null ? network.getSocketFactory() : null);
                    }
                    String name = active.connect(cameraHost, cameraPort);
                    if (!connectAttemptActive(generation, true)) {
                        closeQuietly(active);
                        return;
                    }
                    active.assertHealthy();
                    if (!connectAttemptActive(generation, true)) {
                        closeQuietly(active);
                        return;
                    }
                    mainHandler.post(() -> {
                        if (!connectAttemptActive(generation, true)) return;
                        reconnecting = false;
                        connecting = false;
                        connected = true;
                        cameraName = name;
                        reconnectAttempt = 0;
                        listener.onStatus("已自动重连 · " + name);
                        listener.onConnected(name);
                        acquireWifiLock();
                        startHeartbeat();
                        registerWifiNetworkCallback();
                    });
                } catch (Exception error) {
                    CameraTransport active = transport;
                    closeQuietly(active);
                    if (!connectAttemptActive(generation, true)) return;
                    mainHandler.post(() -> {
                        if (!connectAttemptActive(generation, true)) return;
                        connecting = false;
                        scheduleReconnect();
                    });
                }
            });
        }
    };

    public CameraSessionCoordinator(
            Context context,
            UsbPtpTransport.PermissionGate usbPermissionGate,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.repository = new PhotoRepository(this.context);
        this.galleryExporter = new GalleryExporter(this.context);
        this.previewCacheDir = new File(this.context.getCacheDir(), "preview");
        long previewCacheBudget = Math.max(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        this.previewCache = new LruCache<Long, Bitmap>((int) previewCacheBudget) {
            @Override protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount();
            }
        };
        this.usbTransport = new UsbPtpTransport(this.context, usbPermissionGate);
        this.connectivityManager =
                (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifiManager =
                (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        // 持久化发起方 GUID：同一台手机每次握手复用同一身份，相机有机会把它识别为
        // 「同一设备」从而允许替换旧连接（P6 实验）。首次生成后固定不变。
        wifiTransport.setInitiatorGuid(loadOrCreateInitiatorGuid(this.context));
        // 只有 PTP/IP 有 event 通道；USB 的新对象发现走句柄差集轮询。
        wifiTransport.setObjectEventListener((storageId, objectHandle) -> {
            NewPhotoResolver resolver = activeResolver;
            if (resolver != null) resolver.onObjectAdded(storageId, objectHandle);
        });
    }

    /** 与 MainActivity 共用的偏好文件名。 */
    private static final String PREFS_NAME = "image_fetch_prefs";
    /** 持久化发起方 GUID 的键（存 32 位十六进制串）。 */
    private static final String PREF_INITIATOR_GUID = "ptpip_initiator_guid";

    /**
     * 读取持久化的 PTP/IP 发起方 GUID；首次运行时随机生成 16 字节并落盘。
     * 落盘失败（如磁盘不可写）不致命：本次仍复用内存中的值，仅退化为「本次运行内固定」。
     */
    private static byte[] loadOrCreateInitiatorGuid(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREF_INITIATOR_GUID, null);
        if (stored != null && stored.length() == 32) {
            byte[] guid = new byte[16];
            boolean valid = true;
            for (int i = 0; i < 16; i++) {
                try {
                    guid[i] = (byte) Integer.parseInt(stored.substring(i * 2, i * 2 + 2), 16);
                } catch (NumberFormatException bad) {
                    valid = false;
                    break;
                }
            }
            if (valid) return guid;
        }
        byte[] guid = new byte[16];
        new java.security.SecureRandom().nextBytes(guid);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : guid) hex.append(String.format(Locale.ROOT, "%02X", b));
        prefs.edit().putString(PREF_INITIATOR_GUID, hex.toString()).apply();
        return guid;
    }

    public File outputDirectory() {
        return repository.outputDirectory();
    }

    /** 是否在落盘后再导出到系统相册（关闭时只保留应用内副本）。 */
    public void setExportToGallery(boolean value) {
        exportToGallery = value;
    }

    /** 每页张数。 */
    public int pageSize() {
        return pageSize;
    }

    /** 设置每页张数（1..500）；调用方负责持久化并在需要时回到第一页。 */
    public void setPageSize(int value) {
        pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, value));
    }

    /** 存储卡结构变化后（拍摄、手动刷新）清掉句柄缓存与完整扫描缓存。 */
    public void invalidateStorageCache() {
        fullScanItems = null;
        PtpOperationService session = operations;
        if (session != null) session.invalidateStorageCache();
    }

    /** 一页读取结果回调。 */
    public interface StoragePageCallback {
        void onLoaded(CameraStorage.Page page);

        void onError(String message, Throwable error);
    }

    /**
     * 读取一页。
     *
     * @param fullScan true = 完整扫描整卡后在内存分页（无目录深度假设，首次较慢，
     *                 结果按会话缓存，翻页瞬时）；false = 快速分页，只读当前页。
     */
    public void loadStoragePage(
            final int pageIndex, final boolean fullScan, final StoragePageCallback callback) {
        executor.submit(() -> {
            final PtpOperationService session = operations;
            if (session == null || !isConnected()) {
                mainHandler.post(() -> callback.onError("请先连接相机。", null));
                return;
            }
            try {
                CameraStorage.Page page;
                if (fullScan) {
                    List<CameraStorage.Item> all = fullScanItems;
                    if (all == null) {
                        listener.onStatus("完整扫描存储卡（整卡枚举，可能较慢）…");
                        all = session.snapshot(0).items;
                        fullScanItems = all;
                    }
                    page = CameraStorage.Page.ofItems(all, pageSize, pageIndex);
                } else {
                    page = session.page(pageSize, pageIndex);
                }
                final CameraStorage.Page result = page;
                mainHandler.post(() -> callback.onLoaded(result));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(
                        error.getMessage() == null ? "读取存储卡失败" : error.getMessage(), error));
            }
        });
    }

    /** 单张缩略图就绪回调；jpeg 为 null 表示该对象无缩略图或读取失败。 */
    public interface ThumbnailCallback {
        void onThumbnail(long handle, byte[] jpeg);
    }

    /**
     * 逐张读取缩略图。与其它相机事务共用同一 executor，因此不会与下载/拍摄交错；
     * 命中缓存直接回调。每次调用产生新的 generation，旧批次会被放弃。
     */
    public void loadThumbnails(
            final List<CameraStorage.Item> items, final ThumbnailCallback callback) {
        final long generation = ++thumbnailGeneration;
        final PtpOperationService session = operations;
        if (session == null || items == null || items.isEmpty()) return;
        executor.submit(() -> {
            for (CameraStorage.Item item : items) {
                if (generation != thumbnailGeneration) return;
                byte[] cached = thumbnailCache.get(item.handle);
                if (cached != null) {
                    postThumbnail(generation, callback, item.handle, cached);
                    continue;
                }
                byte[] jpeg = null;
                try {
                    byte[] read = session.thumbnail(item.handle);
                    if (read != null && read.length > 0) {
                        jpeg = read;
                        thumbnailCache.put(item.handle, read);
                    }
                } catch (Exception ignored) {
                    // 单个对象没有缩略图（RAW/视频/机型不支持）属正常，不中断整批。
                }
                if (generation != thumbnailGeneration) return;
                postThumbnail(generation, callback, item.handle, jpeg);
            }
        });
    }

    private void postThumbnail(
            long generation, ThumbnailCallback callback, long handle, byte[] jpeg) {
        mainHandler.post(() -> {
            if (generation == thumbnailGeneration) callback.onThumbnail(handle, jpeg);
        });
    }

    /** 放大预览回调；bitmap 为 null 表示取不到原图（RAW/视频/相机不支持），沿用缩略图。 */
    public interface PreviewCallback {
        void onPreview(long handle, Bitmap bitmap);
    }

    /**
     * 读取一张原图用于放大预览。只有 JPEG 才向相机取原图（RAW/视频不适用），
     * 解码后按句柄缓存；与其它相机事务共用 executor，不会与下载/翻页交错。
     * 连续点更多张时，排队的旧请求会被放弃，不重复占用带宽。
     */
    public void loadPreview(final CameraStorage.Item item, final PreviewCallback callback) {
        if (item == null) return;
        final long generation = ++previewGeneration;
        executor.submit(() -> {
            Bitmap bitmap = previewCache.get(item.handle);
            if (bitmap == null && item.isJpeg() && generation == previewGeneration) {
                bitmap = fetchPreview(item.handle);
            }
            if (generation != previewGeneration) return;
            final Bitmap result = bitmap;
            mainHandler.post(() -> callback.onPreview(item.handle, result));
        });
    }

    /** 原图落盘到缓存目录 → 降采样解码 → 删除临时文件；失败返回 null（回落缩略图）。 */
    private Bitmap fetchPreview(long handle) {
        final PtpOperationService session = operations;
        if (session == null || !isConnected()) return null;
        File temp = null;
        try {
            if (!previewCacheDir.exists() && !previewCacheDir.mkdirs()) return null;
            temp = new File(previewCacheDir, handle + ".jpg");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                session.download(handle, output, null);
                output.flush();
            }
            Bitmap bitmap = decodeFile(temp, PREVIEW_MAX_PX);
            if (bitmap != null) previewCache.put(handle, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    /** 两遍解码：先读尺寸算 inSampleSize，避免整张原图位图撑爆内存。 */
    private static Bitmap decodeFile(File file, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (targetPx > 0 && longest / sample > targetPx * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    public boolean isConnected() {
        CameraTransport active = transport;
        return connected && active != null && active.isConnected();
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public String cameraName() {
        return cameraName;
    }

    /** 退避序列（纯函数，便于契约测试）：1/2/4/8/16 封顶 30s。 */
    static long backoffDelayMs(int attempt) {
        return ReconnectPolicy.backoffDelayMs(attempt);
    }

    /**
     * 连接相机。Wi‑Fi 用 host/port；USB 忽略两者，按已连接的 USB 相机建立会话。
     * 切换传输方式时会先释放上一条会话。
     */
    public void connect(TransportKind requestedKind, String host, int port) {
        manualDisconnect = false;
        reconnecting = false;
        connecting = true;
        heartbeat.reset();
        reconnectAttempt = 0;
        // 用户主动连接优先于任何在途的自动重连。
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        cameraHost = host;
        cameraPort = port;
        final long generation = ++connectionGeneration;
        executor.submit(() -> {
            try {
                closeCurrentTransport();
                CameraTransport active = selectTransport(requestedKind);
                if (requestedKind == TransportKind.USB) {
                    listener.onStatus("枚举 USB 相机…");
                } else {
                    listener.onStatus("枚举 Wi‑Fi 网络…");
                    seedCameraWifiNetwork();
                    registerWifiNetworkCallback();
                    Network network = liveCameraWifiNetwork();
                    sessionNetwork = network;
                    ((PtpIpTransport) active).setSocketFactory(
                            network != null ? network.getSocketFactory() : null);
                    listener.onStatus("建立 PTP/IP 双通道…");
                }
                String name = active.connect(host, port);
                if (!connectAttemptActive(generation, false)) {
                    closeQuietly(active);
                    return;
                }
                active.assertHealthy();
                if (!connectAttemptActive(generation, false)) {
                    closeQuietly(active);
                    return;
                }
                mainHandler.post(() -> {
                    if (!connectAttemptActive(generation, false)) return;
                    connected = true;
                    connecting = false;
                    cameraName = name;
                    reconnectAttempt = 0;
                    listener.onStatus("已连接 · " + name);
                    listener.onConnected(name);
                    acquireWifiLock();
                    startHeartbeat();
                });
            } catch (Exception error) {
                CameraTransport active = transport;
                closeQuietly(active);
                if (!connectAttemptActive(generation, false)) return;
                final String detail = connectFailureMessage(requestedKind, host, port, error);
                mainHandler.post(() -> {
                    if (!connectAttemptActive(generation, false)) return;
                    connected = false;
                    connecting = false;
                    listener.onError(detail, error);
                });
            }
        });
    }

    /**
     * 连接失败提示 = 用户可读的原因与建议 + 真机诊断。
     * 诊断段是排查唯一依据：网络选择、本机出口、逐次握手的异常链、上次会话释放结果，
     * 都要能在一次真机报错里看到，不必再连 logcat。
     */
    private String connectFailureMessage(
            TransportKind kind, String host, int port, Throwable error) {
        StringBuilder text = new StringBuilder();
        text.append(error.getMessage() != null ? error.getMessage() : "连接失败");
        text.append(unreachableHint(error));
        text.append("\n\n——— 诊断 ———");
        text.append("\n传输：").append(kind == TransportKind.USB
                ? "USB"
                : "Wi‑Fi " + host + ":" + port);
        text.append("\n系统：Android ").append(Build.VERSION.RELEASE)
                .append("（SDK ").append(Build.VERSION.SDK_INT).append("）");
        text.append("\n上次断开：")
                .append(lastSessionRelease.isEmpty() ? "未记录" : lastSessionRelease);
        CameraTransport active = transport;
        if (active instanceof PtpIpTransport) {
            text.append('\n').append(((PtpIpTransport) active).connectDiagnostics());
        }
        return text.toString();
    }

    /** 「拍摄超时」时附上厂商快门逐步诊断，区分「相机没拍」与「拍了没被发现」。 */
    private String captureDiagnosticsSuffix() {
        PtpOperationService active = operations;
        if (active == null) return "";
        String steps = active.transport().captureDiagnostics();
        return steps.isEmpty() ? "" : "\n快门诊断：\n" + steps;
    }

    /**
     * 相机不可达（EHOSTUNREACH / No route to host）几乎都不是协议问题，而是网络路径断了：
     * 手机被系统切回移动数据或其它 Wi‑Fi、相机休眠/自动关机、或相机自身超时断开了 Wi‑Fi。
     * 这类失败靠重试无法自愈，只能给出可操作的排查方向。
     */
    static String unreachableHint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String text = cause.getMessage();
            if (text != null && text.contains("ECONNABORTED")) {
                return "\n\n连接过程被中断（ECONNABORTED）：TCP 握手还没完成，链路就断了。\n"
                        + "1) 相机很可能已退出「连接智能手机」模式或进入休眠——"
                        + "在相机上重新进入该模式；\n"
                        + "2) 相机设置里的 Wi‑Fi 节能 / 空闲断开 / 自动关机是否开启（开启就关掉）；\n"
                        + "3) 手机是否在连接瞬间从相机 Wi‑Fi 切回了移动数据。";
            }
            if (text != null
                    && (text.contains("ECONNREFUSED") || text.contains("Connection refused"))) {
                return "\n\n相机 Wi‑Fi 通着，但相机没有在 15740 端口监听（PTP 服务没起来）。"
                        + "常见原因：\n"
                        + "1) 相机刚结束上一次连接，PTP 服务还在重启——等几秒再点「连接」；\n"
                        + "2) 相机已退出「连接智能手机」模式（相机屏上可能显示「未找到连接目标」），"
                        + "需要在相机上重新进入该模式；\n"
                        + "3) 部分机型需在相机菜单里确认已开启 PTP/IP（无线连接）服务。";
            }
            boolean unreachable = cause instanceof NoRouteToHostException
                    || cause instanceof UnknownHostException
                    || (cause.getMessage() != null
                            && (cause.getMessage().contains("EHOSTUNREACH")
                                    || cause.getMessage().contains("No route to host")));
            if (!unreachable) continue;
            return "\n\n相机在当前网络里不可达。请依次检查：\n"
                    + "1) 手机是否仍连着相机的 Wi‑Fi（系统常会自动切回移动数据或已记住的其它 Wi‑Fi）；\n"
                    + "2) 相机是否已休眠/自动关机（相机设置里关闭「自动关机」等省电项）；\n"
                    + "3) 相机的 Wi‑Fi 节能/空闲断开选项是否开启。\n"
                    + "以上任一项都会让相机从网络中消失，必须手动恢复后再连接。";
        }
        return "";
    }

    /** 选定传输实现并重建操作门面（executor 内串行调用，无并发切换）。 */
    private CameraTransport selectTransport(TransportKind requestedKind) {
        CameraTransport active = requestedKind == TransportKind.USB ? usbTransport : wifiTransport;
        transport = active;
        operations = new PtpOperationService(active);
        // 句柄在同一相机的不同会话间可复用，跨会话沿用旧缓存可能张冠李戴。
        thumbnailCache.evictAll();
        previewCache.evictAll();
        fullScanItems = null;
        return active;
    }

    private void closeCurrentTransport() {
        closeQuietly(transport);
    }

    private static void closeQuietly(CameraTransport transport) {
        if (transport == null) return;
        try {
            transport.close();
        } catch (IOException ignored) {
            // 关闭阶段的 IO 错误不影响会话状态复位。
        }
    }

    /**
     * 把已落盘的原图导出到系统相册（DCIM/IMAGE_FETCH）。失败只上报、不回滚，也不中断后续传输：
     * 应用文件库中的副本始终是可用结果，相册导出是附加可见性。
     * 失败信息每批操作只弹一次，避免批量下载时反复打断。
     */
    private String exportToGalleryIfEnabled(File file) {
        // 明确写出“没导出”的原因，避免与“导出失败”混淆（状态栏文字是唯一线索）。
        if (!exportToGallery) return " · 未勾选「保存到系统相册」";
        try {
            galleryExporter.export(file, file.getName());
            // 只留相册：导出成功后清掉私有目录副本，避免同一张照片占两份空间。
            // 去重索引（.imported-index）按对象身份记录，不依赖副本存在，跳过重复下载的能力不受影响。
            if (!file.delete()) {
                return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH + "（副本未能删除）";
            }
            return " · 已存入系统相册 " + GalleryExporter.RELATIVE_PATH;
        } catch (Exception error) {
            String reason = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            if (!exportFailureReported) {
                exportFailureReported = true;
                mainHandler.post(() -> listener.onError(
                        "照片已保存到应用内副本，但导出到系统相册失败：" + reason, null));
            }
            return " · 相册导出失败：" + reason;
        }
    }

    /** 手动断开：不触发自动重连。 */
    public void disconnect() {
        connectionGeneration++;
        manualDisconnect = true;
        reconnecting = false;
        connecting = false;
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        unregisterWifiNetworkCallback();
        releaseWifiLock();
        cameraWifiNetwork = null;
        sessionNetwork = null;
        final CameraTransport session = transport;
        TransferControl current = control;
        if (current != null) {
            current.cancel("用户断开连接");
            // 传输中：必须立刻打断阻塞中的读写。
            if (session != null) session.abortTransport();
            lastSessionRelease = "传输中被强制中断（未主动释放相机侧会话）";
        } else {
            lastSessionRelease = "未主动释放相机侧会话（统一策略：断开只拆 TCP，保留槽位）";
        }
        executor.submit(() -> {
            // 统一策略：Wi‑Fi 断开一律不发 CloseSession(0x1003)。
            // 实测发 CloseSession 后相机会关掉自己的 PTP/IP 监听（下次连接 ECONNREFUSED），
            // 且 InitFail 是连接层拒绝，CloseSession 也动不了它。断开只拆 TCP，把会话槽位
            // 留给相机的超时回收；重连时由 OpenSession 的 0x201E 复用。诊断见 lastSessionRelease。
            closeQuietly(session);
            connected = false;
            cameraName = "";
            heartbeat.reset();
            mainHandler.post(listener::onDisconnected);
        });
    }

    public void shutdown() {
        manualDisconnect = true;
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        CameraTransport session = transport;
        TransferControl current = control;
        if (current != null) {
            current.cancel("应用退出");
            // 传输中：先打断阻塞读写，否则 close() 取实例锁时会干等（可能触发 ANR），
            // 且通道正忙时也无法优雅释放会话。
            if (session != null) session.abortTransport();
        }
        closeQuietly(session);
        unregisterWifiNetworkCallback();
        releaseWifiLock();
        executor.shutdownNow();
    }

    public void cancelCurrent() {
        TransferControl current = control;
        if (current != null) current.cancel("用户取消");
    }

    /** 触发一次拍摄并自动取回新产生的 JPEG 原图（无 JPEG 时跳过，不保存 RAW）。 */
    public void captureAndFetch() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onError("请先连接相机。", null));
                return;
            }
            TransferControl current = new TransferControl();
            control = current;
            exportFailureReported = false;
            try {
                listener.onStatus("读取拍摄前句柄快照…");
                CameraStorage.Snapshot baseline = operations.snapshot(CAPTURE_BASELINE_ITEMS);
                NewPhotoResolver resolver = new NewPhotoResolver(
                        operations.cameraIdentity(), repository::isImported);
                resolver.recordBaseline(baseline);
                activeResolver = resolver;

                listener.onStatus("触发相机快门…");
                long[] directHandles = operations.capture();

                if (directHandles.length > 0) {
                    // 相机把图像直接放进机身内存（Nikon USB SDRAM 路径）：句柄已知，
                    // 不需要句柄差集发现，但仍复用同一流式落盘与去重流程。
                    // 去重键带本次拍摄令牌：SDRAM 兜底句柄是固定值，不能跨拍摄复用。
                    String captureToken = "capture:" + System.nanoTime();
                    for (long handle : directHandles) {
                        current.throwIfCancelled();
                        CameraStorage.Item item = syntheticCaptureItem(handle);
                        listener.onStatus("取回机身内存中的新图像 " + item.filename + " …");
                        PhotoTransferWorker worker =
                                new PhotoTransferWorker(operations, repository);
                        PhotoTransferWorker.Result result = worker.transfer(
                                item,
                                (received, total) -> listener.onProgress(received, total),
                                current,
                                captureToken + ":" + handle);
                        if (result.alreadyImported) {
                            listener.onStatus(item.filename + " 已入库，跳过重复落盘。");
                            continue;
                        }
                        String note = exportToGalleryIfEnabled(result.file);
                        listener.onStatus("已保存 " + result.file.getName()
                                + " · " + result.bytes + " 字节 · "
                                + result.elapsedMillis + " ms" + note);
                        mainHandler.post(() -> listener.onFileSaved(
                                result.file, result.bytes, result.elapsedMillis, note));
                    }
                    return;
                }

                listener.onStatus("等待相机写卡并发现新对象…");
                NewPhotoResolver.Resolution resolution = resolver.resolveUntil(
                        operations,
                        CAPTURE_DISCOVERY_TIMEOUT_MS,
                        CAPTURE_POLL_INTERVAL_MS,
                        CAPTURE_BASELINE_ITEMS,
                        item -> !item.isVideo(),
                        current);

                // 只保存 JPEG：RAW（.cr2/.cr3/.nef…）等不落盘。
                // 相机设为仅 RAW 时只提示、不报错——这不算失败，只是没有可取回的格式。
                List<CameraStorage.Item> jpegs = new ArrayList<>();
                List<CameraStorage.Item> skipped = new ArrayList<>();
                for (CameraStorage.Item item : resolution.newItems) {
                    if (item.isJpeg()) jpegs.add(item);
                    else skipped.add(item);
                }
                if (jpegs.isEmpty()) {
                    if (!skipped.isEmpty()) {
                        listener.onStatus("本次未发现 JPEG，仅有 " + names(skipped)
                                + "；按设置（无 JPEG 不保存）跳过。");
                        return;
                    }
                    throw new Exception(
                            "超时未发现本次拍摄的新对象（可能仍在写卡、事件缺失或返回了不受支持的格式，"
                                    + "也可能是相机根本没执行快门）。"
                                    + captureDiagnosticsSuffix());
                }
                if (!skipped.isEmpty()) {
                    listener.onStatus("跳过非 JPEG 对象：" + names(skipped));
                }

                listener.onStatus("发现新照片："
                        + (resolution.discoveredByEvent ? "事件通道" : "轮询差集")
                        + " · " + names(jpegs));
                for (CameraStorage.Item item : jpegs) {
                    current.throwIfCancelled();
                    listener.onStatus("下载 " + item.filename
                            + "（声明 " + item.sizeBytes + " 字节）…");
                    PhotoTransferWorker worker =
                            new PhotoTransferWorker(operations, repository);
                    PhotoTransferWorker.Result result = worker.transfer(
                            item,
                            (received, total) -> listener.onProgress(received, total),
                            current);
                    if (result.alreadyImported) {
                        listener.onStatus(item.filename + " 已入库，跳过重复落盘。");
                        continue;
                    }
                    String note = exportToGalleryIfEnabled(result.file);
                    listener.onStatus("已保存 " + result.file.getName()
                            + " · " + result.bytes + " 字节 · "
                            + result.elapsedMillis + " ms" + note);
                    mainHandler.post(() -> listener.onFileSaved(
                            result.file, result.bytes, result.elapsedMillis, note));
                }
            } catch (InterruptedException interrupted) {
                mainHandler.post(() -> listener.onStatus(interruptedMessage(current)));
            } catch (Exception error) {
                mainHandler.post(() -> listener.onError(
                        error.getMessage() != null ? error.getMessage() : "拍摄取回失败", error));
            } finally {
                activeResolver = null;
                control = null;
                lastTransferFinishedAt = System.currentTimeMillis();
                // 快门可能已改变卡内对象，句柄缓存与完整扫描缓存都不能再用。
                invalidateStorageCache();
            }
        });
    }

    /** 手动下载选中的相机对象（沿用同一去重与原子落盘流程）。 */
    public void download(List<CameraStorage.Item> items) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onError("请先连接相机。", null));
                return;
            }
            if (items == null || items.isEmpty()) {
                mainHandler.post(() -> listener.onError("未选择要下载的对象。", null));
                return;
            }
            TransferControl current = new TransferControl();
            control = current;
            exportFailureReported = false;
            try {
                for (CameraStorage.Item item : items) {
                    current.throwIfCancelled();
                    listener.onStatus("下载 " + item.filename + " …");
                    PhotoTransferWorker worker =
                            new PhotoTransferWorker(operations, repository);
                    PhotoTransferWorker.Result result = worker.transfer(
                            item,
                            (received, total) -> listener.onProgress(received, total),
                            current);
                    if (result.alreadyImported) {
                        listener.onStatus(item.filename + " 已入库，跳过。");
                        continue;
                    }
                    // 与拍摄路径一致：落盘成功后按用户开关导出到系统相册。
                    String note = exportToGalleryIfEnabled(result.file);
                    listener.onStatus("已保存 " + result.file.getName()
                            + " · " + result.bytes + " 字节 · " + result.elapsedMillis + " ms"
                            + note);
                    mainHandler.post(() -> listener.onFileSaved(
                            result.file, result.bytes, result.elapsedMillis, note));
                }
            } catch (InterruptedException interrupted) {
                mainHandler.post(() -> listener.onStatus(interruptedMessage(current)));
            } catch (Exception error) {
                mainHandler.post(() -> listener.onError(
                        error.getMessage() != null ? error.getMessage() : "下载失败", error));
            } finally {
                control = null;
                lastTransferFinishedAt = System.currentTimeMillis();
                mainHandler.post(listener::onDownloadFinished);
            }
        });
    }

    private static String interruptedMessage(TransferControl control) {
        String reason = control == null ? null : control.reason();
        return reason == null ? "已取消当前操作。" : "操作已中断：" + reason;
    }

    private static String names(List<CameraStorage.Item> items) {
        StringBuilder builder = new StringBuilder();
        for (CameraStorage.Item item : items) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(item.filename);
        }
        return builder.toString();
    }

    /**
     * 为机内内存对象（SDRAM 句柄）构造一个合成条目：这类句柄没有卡内 ObjectInfo，
     * 因此尺寸未知（-1，落盘时按实际字节数校验）、存储卷未知（0）。
     * 文件名带时间戳，便于人工辨认。
     */
    private static CameraStorage.Item syntheticCaptureItem(long handle) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return new CameraStorage.Item(
                handle,
                0L,
                0,
                "CAPTURE_" + stamp + ".JPG",
                -1L,
                0,
                0,
                stamp,
                false);
    }

    // ── 心跳与自动重连 ──

    private void startHeartbeat() {
        startHeartbeat(ReconnectPolicy.HEARTBEAT_INTERVAL_MS);
    }

    private void startHeartbeat(long intervalMillis) {
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.postDelayed(heartbeatRunnable, intervalMillis);
    }

    /** 连续 3 次心跳无响应 / 绑定网络丢失 → 判离线，进入重连态。 */
    private void enterReconnecting() {
        if (!connected || reconnecting || manualDisconnect) return;
        connectionGeneration++;
        reconnecting = true;
        connected = false;
        heartbeat.reset();
        reconnectAttempt = 0;
        mainHandler.removeCallbacks(heartbeatRunnable);
        unregisterWifiNetworkCallback();
        // 绑定网络随会话作废；候选缓存保留，供紧接的自动重连复用。
        sessionNetwork = null;
        TransferControl current = control;
        if (current != null) current.cancel("相机链路丢失");
        final CameraTransport session = transport;
        if (session != null) {
            session.abortTransport();
            executor.submit(() -> closeQuietly(session));
        }
        listener.onStatus("相机链路丢失，进入自动重连…");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!reconnecting || manualDisconnect) return;
        reconnectAttempt++;
        long delay = backoffDelayMs(reconnectAttempt);
        mainHandler.post(() -> {
            if (!reconnecting || manualDisconnect) return;
            listener.onReconnecting(reconnectAttempt, delay);
        });
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private boolean connectAttemptActive(long generation, boolean reconnect) {
        if (manualDisconnect || !connecting || generation != connectionGeneration) {
            return false;
        }
        return reconnect == reconnecting;
    }

    // ── 相机 Wi‑Fi 精确路由（移植自 ZENCHE MainActivity） ──

    private void registerWifiNetworkCallback() {
        if (wifiNetworkCallback != null || connectivityManager == null) return;
        final long callbackGeneration = connectionGeneration;
        wifiNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                mainHandler.post(() -> {
                    if (wifiNetworkCallback == this
                            && callbackGeneration == connectionGeneration) {
                        cameraWifiNetwork = network;
                    }
                });
            }

            @Override public void onLost(Network network) {
                mainHandler.post(() -> {
                    Network bound = sessionNetwork != null ? sessionNetwork : cameraWifiNetwork;
                    if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                        cameraWifiNetwork = null;
                    }
                    if (bound == null || !bound.equals(network)) return;
                    if (wifiNetworkCallback == this
                            && connected
                            && !manualDisconnect) {
                        listener.onStatus("相机 Wi‑Fi 网络已丢失。");
                        enterReconnecting();
                    }
                });
            }
        };
        try {
            connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(),
                    wifiNetworkCallback);
        } catch (Exception ignored) {
            wifiNetworkCallback = null;
        }
    }

    /** 同步种子化现有 Wi‑Fi 候选网络：onAvailable 是异步回调，不能假设先于 connect 执行。 */
    private void seedCameraWifiNetwork() {
        if (connectivityManager == null) return;
        Network fallback = null;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                // 优先无互联网的 Wi‑Fi（相机 AP 典型形态）。
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    cameraWifiNetwork = network;
                    return;
                }
                fallback = network;
            }
        } catch (Exception ignored) {
            return;
        }
        if (fallback != null) cameraWifiNetwork = fallback;
    }

    /**
     * 取当前仍然有效的相机 Wi‑Fi 网络。网络可能已被系统切走（此时 Network 句柄失效），
     * 再拿它 getSocketFactory().createSocket() 绑定会直接抛 EPERM；先校验一次可避免。
     */
    private Network liveCameraWifiNetwork() {
        Network network = cameraWifiNetwork;
        if (network == null || connectivityManager == null) return null;
        try {
            return connectivityManager.getNetworkCapabilities(network) != null ? network : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void unregisterWifiNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = wifiNetworkCallback;
        wifiNetworkCallback = null;
        if (callback == null || connectivityManager == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
    }

    /**
     * 连接期间持有 WifiLock：系统默认在息屏待机时可能让 Wi‑Fi 无线电休眠，
     * 大文件传输就会在中途断开。自动重连期间不释放——链路恢复需要无线电保持清醒。
     */
    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        if (wifiManager == null) return;
        try {
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "imagefetch:camera");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
            // 拿不到锁不阻断会话：只是失去息屏保活这一层保护。
        }
    }

    private void releaseWifiLock() {
        WifiManager.WifiLock lock = wifiLock;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }
}
