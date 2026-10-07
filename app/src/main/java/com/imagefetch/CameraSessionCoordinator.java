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
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.LiveViewSize;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * 连接保活的三态（M2）。刻意做成<b>派生值</b>而不是独立字段：真正的状态仍由
     * {@code connected}/{@code reconnecting}/{@code manualDisconnect}/{@code suspended}
     * 承载，避免出现两份互相打架的真相。
     *
     * <ul>
     *   <li>{@link #HOLD}：应用在前台，心跳正常保活。</li>
     *   <li>{@link #SUSPEND}：应用退到后台，停掉心跳探测省电，但保留 TCP socket
     *       与相机侧会话；回到前台立刻补一次探测。</li>
     *   <li>{@link #RELEASE}：用户手动断开或退出应用，彻底释放。</li>
     * </ul>
     */
    public enum HoldState {
        HOLD,
        SUSPEND,
        RELEASE
    }

    /**
     * HOLD 状态推导（纯函数，便于契约测试）。
     * {@code manualDisconnect} 优先级最高；既没连上也不在重连 → 视为已释放。
     */
    static HoldState holdStateOf(
            boolean connected, boolean reconnecting, boolean manualDisconnect, boolean suspended) {
        if (manualDisconnect || (!connected && !reconnecting)) return HoldState.RELEASE;
        return suspended ? HoldState.SUSPEND : HoldState.HOLD;
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

        /** 实时取景的一帧（已解码）；界面替换画面时应回收上一帧。 */
        void onLiveViewFrame(Bitmap frame);

        /** 实时取景状态（已开启/已关闭/中断原因）。 */
        void onLiveViewStatus(String message);

        /**
         * 曝光参数结果：读取或写入成功时 {@code index >= 0}、{@code display} 为文案；
         * 失败时 {@code index < 0}、{@code error} 为原因。
         */
        void onExposureResult(CanonExposure.Setting setting, int index, String display, String error);

        /**
         * 一次「拍摄并取回」流程结束（含未取到 JPEG 与失败）。
         * 「未发现 JPEG」路径既不落盘也不报错，只发 onStatus，
         * 单靠 onFileSaved/onError 会把拍摄按钮永久留在禁用态。
         */
        void onCaptureFinished();

        /** 一次自动对焦流程结束（成功或失败）；界面据此恢复「自动对焦」按钮。 */
        void onAutofocusFinished();

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
    /** 实时取景取帧间隔（约 10fps，与 libgphoto2 的取景循环同量级）。 */
    private static final long LIVE_VIEW_FRAME_INTERVAL_MS = 100L;
    /** 相机「这一帧还没准备好」时的退避间隔。 */
    private static final long LIVE_VIEW_RETRY_INTERVAL_MS = 300L;
    /** 取景被「拍摄序列」挂起时的轮询间隔：只检查标志位，不碰相机。 */
    private static final long LIVE_VIEW_SUSPEND_POLL_MS = 50L;
    /**
     * 取景开启序列的失败重试次数/退避步长。实测相机（刚连上或刚拍完时）会拒绝 EVF 属性写入，
     * 重试几次才真正切进取景；早先只试一次，失败后面板就一直黑着，非拍一张不能恢复。
     */
    private static final int LIVE_VIEW_ENABLE_ATTEMPTS = 5;
    private static final long LIVE_VIEW_ENABLE_BACKOFF_MS = 400L;
    /** 连续多少次取帧抛异常才判定取景真的断了（单次异常多为相机忙，重试即可）。 */
    private static final int LIVE_VIEW_MAX_CONSECUTIVE_ERRORS = 3;
    /**
     * 这么久一帧都拿不到就重发一次开启序列：相机可能对写入回了 OK 却没真正切到取景输出，
     * 只靠重复取帧永远不会出画面。
     */
    private static final long LIVE_VIEW_STALL_RESTART_MS = 4000L;
    /** 相机不回 PONG 时的保活周期：真实命令往返比 PING 重，放慢到 30s。 */
    private static final long COMMAND_PROBE_INTERVAL_MS = 30000L;
    /** 传输结束后的冷却期：相机可能仍在写卡，期间不做保活判定。 */
    private static final long TRANSFER_COOLDOWN_MS = 10000L;
    /** 等待用户在弹出的系统对话框里加入相机 Wi‑Fi 的上限（含用户取消/密码错）。 */
    private static final long SPECIFIER_TIMEOUT_MS = 45_000L;
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
    /** WifiNetworkSpecifier 的请求句柄：与 wifiNetworkCallback 互斥，两者不同时注册。 */
    private volatile ConnectivityManager.NetworkCallback specifierNetworkCallback;
    /** 等待系统对话框结果的闸门；executor 线程阻塞在此，回调在主线程放行。 */
    private volatile CountDownLatch specifierLatch;
    /** specifier 回调加入成功时写入的网络；未加入时为 null。 */
    private final AtomicReference<Network> specifierResult = new AtomicReference<>();
    private Network cameraWifiNetwork;
    private Network sessionNetwork;
    /** 相机 Wi‑Fi 名称/密码；SSID 为空表示不改网络，沿用系统已连的那条。 */
    private volatile String cameraSsid = "";
    private volatile String cameraPassphrase = "";
    /** 会话期间持有：避免息屏/待机时 Wi‑Fi 无线电休眠导致传输中断。 */
    private final WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;

    private volatile long connectionGeneration;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean reconnecting;
    private volatile boolean manualDisconnect;
    /** 应用已退到后台：停心跳探测但保留链路与传输（见 {@link HoldState#SUSPEND}）。 */
    private volatile boolean suspended;
    private final HeartbeatMonitor heartbeat =
            new HeartbeatMonitor(ReconnectPolicy.OFFLINE_THRESHOLD);
    private volatile int reconnectAttempt;
    private volatile String cameraName = "";
    private volatile String cameraHost = "";
    private volatile int cameraPort = 15740;

    private volatile NewPhotoResolver activeResolver;
    private volatile TransferControl control;
    /** 实时取景是否正在取帧；断开/关闭时置 false。 */
    private volatile boolean liveViewActive;
    /** 取景循环代际：每次开/关都自增，让在途的取帧任务自然退出。 */
    private volatile long liveViewGeneration;
    /** 当前取景画面尺寸（相机端推流分辨率）；由面板下拉框切换。 */
    private volatile LiveViewSize liveViewSize = LiveViewSize.LARGE;
    /** 正在跑的取景循环；拍摄期间会暂时断开取景，拍完用它重置「无画面」计时，避免误报停滞。 */
    private volatile LiveViewPump activePump;
    /**
     * 拍摄序列进行中：取帧先让开，别在暂停取景与按快门之间往相机插帧请求。
     * 取帧循环本身跑在独占线程上（见 {@link #startLiveView}），刻意不复用
     * {@link #executor}——那条单线程还要跑界面操作与拍摄，取帧任务一旦排在前面，
     * 每次点击都得先等一帧拉完，这就是「面板打开时整体都卡」的根因。
     */
    private volatile boolean liveViewSuspended;
    /** 相册导出失败是否已提示过（每次拍摄/下载开始时复位）。 */
    private volatile boolean exportFailureReported;
    /** 最近一次手动断开时相机侧会话的释放结果（诊断用，会随下一次连接失败一起展示）。 */
    private volatile String lastSessionRelease = "";

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            // suspended：应用在后台，本 tick 不探测也不重排 → 后台零保活流量。
            if (!connected || reconnecting || manualDisconnect || suspended) return;
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
                    // suspended：挂起瞬间可能还有一帧在途的探测结果，此时不能据此判离线。
                    if (!connected || manualDisconnect || suspended
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
            executor.submit(() -> {
                try {
                    CameraTransport active = transport;
                    if (active instanceof PtpIpTransport) {
                        bindWifiNetwork((PtpIpTransport) active, generation, true);
                        if (!connectAttemptActive(generation, true)) {
                            closeQuietly(active);
                            return;
                        }
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
        // 新会话从活跃态起步：上一次退后台留下的挂起标记不能带过来。
        suspended = false;
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
                    bindWifiNetwork((PtpIpTransport) active, generation, false);
                    if (!connectAttemptActive(generation, false)) {
                        closeQuietly(active);
                        return;
                    }
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
        // 换传输即换会话，取景状态不能再沿用。
        invalidateLiveView();
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

    // ── 连接保活 HOLD 三态（前台活跃 / 后台暂缓 / 释放） ──

    /** 当前 HOLD 状态（派生值，语义见 {@link HoldState}）。 */
    public HoldState holdState() {
        return holdStateOf(connected, reconnecting, manualDisconnect, suspended);
    }

    /**
     * 应用退到后台：停掉心跳探测（省电，也避免后台一次网络抖动就误判离线），
     * 但不拆链路——socket、相机侧会话、WifiLock 全部保留，传输/拍摄/取景照常继续。
     * 回到前台由 {@link #resumeHold()} 重新武装探测。
     */
    public void suspendHold() {
        if (manualDisconnect) return;
        suspended = true;
        mainHandler.removeCallbacks(heartbeatRunnable);
    }

    /**
     * 应用回到前台：立刻补一次探测（0 延迟复用既有心跳，不新增探测路径）。
     * 不显式重置心跳计数：探测成功自身会清零，失败则与挂起前的历史合并，能更快判定真离线。
     */
    public void resumeHold() {
        if (!suspended) return;
        suspended = false;
        if (connected && !reconnecting && !manualDisconnect) startHeartbeat(0L);
    }

    /** 手动断开：不触发自动重连。 */
    public void disconnect() {
        connectionGeneration++;
        // 取景循环必须先停：断开后相机会退出取景，残留的取帧任务只会反复报错。
        invalidateLiveView();
        manualDisconnect = true;
        suspended = false;
        reconnecting = false;
        connecting = false;
        mainHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        unregisterWifiNetworkCallback();
        unregisterSpecifierCallback();
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
        suspended = false;
        invalidateLiveView();
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
        unregisterSpecifierCallback();
        releaseWifiLock();
        executor.shutdownNow();
    }

    public void cancelCurrent() {
        TransferControl current = control;
        if (current != null) current.cancel("用户取消");
    }

    // ── 实时取景与曝光参数（Canon EOS） ──

    /**
     * 作废正在跑的取景循环（断开、重连、切换传输、退出应用）。
     * 直接改标志位而不经 executor：这些路径可能正被在途的相机 IO 占着线程，
     * 排队等它反而会拖住断开；{@code liveViewGeneration} 自增即可让在途帧任务自行退出。
     */
    private void invalidateLiveView() {
        liveViewActive = false;
        liveViewGeneration++;
        activePump = null;
        liveViewSuspended = false;
    }

    /**
     * 开启实时取景预览。取帧循环跑在独占线程上（见下），与界面/拍摄共用的是传输层的
     * 命令锁：谁先拿到锁谁发命令，不会交错。
     *
     * <p>开启序列要退避重试几次：相机刚连上或刚拍完时可能拒绝 EVF 属性写入，只试一次会让
     * 面板一直黑着（实测非要先拍一张才能出画面）。
     */
    public void startLiveView() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                return;
            }
            if (liveViewActive) return;
            long generation = liveViewGeneration;
            LiveViewSize size = liveViewSize;
            Exception lastError = null;
            for (int attempt = 1; attempt <= LIVE_VIEW_ENABLE_ATTEMPTS; attempt++) {
                if (!isConnected() || generation != liveViewGeneration) return;
                try {
                    operations.setLiveViewEnabled(true, size);
                    lastError = null;
                    break;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < LIVE_VIEW_ENABLE_ATTEMPTS) {
                        sleepQuietly(LIVE_VIEW_ENABLE_BACKOFF_MS * attempt);
                    }
                }
            }
            if (lastError != null) {
                String message = lastError.getMessage() != null
                        ? lastError.getMessage() : "实时取景开启失败";
                mainHandler.post(() -> listener.onLiveViewStatus(message));
                return;
            }
            liveViewActive = true;
            long activeGeneration = ++liveViewGeneration;
            mainHandler.post(() -> listener.onLiveViewStatus("实时取景已开启"));
            LiveViewPump pump = new LiveViewPump();
            activePump = pump;
            // 取帧循环走独占线程：绝不再往共享 executor 里递归提交，否则每条界面命令
            // 都要排在待取的那一帧后面。停止靠 liveViewActive/generation 标志位。
            Thread thread = new Thread(
                    () -> pumpLiveViewLoop(activeGeneration, pump), "imagefetch-liveview");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * 切换取景画面尺寸（相机端推流分辨率）。取景进行中时立刻改写 0xD1B0 的输出位，
     * 下一帧起就是新分辨率；未开取景时只记下来，下次开取景生效。
     */
    public void setLiveViewSize(LiveViewSize size) {
        if (size == null || size == liveViewSize) return;
        liveViewSize = size;
        if (!liveViewActive) return;
        executor.submit(() -> {
            if (!liveViewActive || !isConnected()) return;
            try {
                operations.setLiveViewEnabled(true, size);
            } catch (Exception ignored) {
                // 改尺寸失败不影响正在进行的取景：继续按原分辨率推画面。
            }
        });
    }

    /**
     * 关闭实时取景。可从主线程直接调用（例如对话框关闭时）。
     *
     * <p>即使取景没开着也照发一次关闭序列：它同时负责把机身屏还回去
     * （EVFMode=0 → SetRemoteMode(0)/(1)，见 PtpIpTransport#setLiveViewEnabled），
     * 用户要求「关闭面板时恢复相机显示」，所以不能提前返回。
     */
    public void stopLiveView() {
        invalidateLiveView();
        executor.submit(() -> {
            try {
                operations.setLiveViewEnabled(false, liveViewSize);
            } catch (Exception ignored) {
                // 关闭失败不影响会话；断开连接时相机会自行退出取景。
            }
            mainHandler.post(() -> listener.onLiveViewStatus("实时取景已关闭，相机显示已恢复"));
        });
    }

    /** 取帧循环的可变状态；同一时刻只有一个循环在跑（由 generation 保证）。 */
    private static final class LiveViewPump {
        int consecutiveErrors;
        /** 已经因为「收不到帧」重发过几次开启序列。 */
        int stallRestarts;
        long lastFrameAt = System.currentTimeMillis();
        long lastRestartAt = System.currentTimeMillis();
    }

    /**
     * 取帧循环主体（独占线程）：只要代际没被作废就一直取帧。
     *
     * <p>被「拍摄序列」挂起时只轮询标志位，完全不碰相机——暂停取景与按快门之间插一帧
     * 请求只会让相机更忙。
     */
    private void pumpLiveViewLoop(long generation, LiveViewPump pump) {
        while (liveViewActive && generation == liveViewGeneration) {
            if (liveViewSuspended) {
                if (!sleepQuietly(LIVE_VIEW_SUSPEND_POLL_MS)) return;
                continue;
            }
            if (!pumpLiveViewFrame(generation, pump)) return;
        }
    }

    /**
     * 取一帧并休眠。用 {@code generation} 判断自己是否已被关闭/重连作废；
     * 返回 {@code false} 表示循环该结束了。
     *
     * <p>不把单次取帧异常当成断开：相机忙时抛一次很常见，连续
     * {@code LIVE_VIEW_MAX_CONSECUTIVE_ERRORS} 次才算真的中断。若长时间一帧都拿不到
     * （相机对开启写入回了 OK 却没真正切开取景输出），重发一次开启序列。
     */
    private boolean pumpLiveViewFrame(long generation, LiveViewPump pump) {
        if (!liveViewActive || generation != liveViewGeneration) return false;
        long delay = LIVE_VIEW_FRAME_INTERVAL_MS;
        boolean gotFrame = false;
        try {
            byte[] jpeg = operations.liveViewFrame();
            if (jpeg == null) {
                // 相机还没准备好这一帧：正常情况，退避后再来。
                delay = LIVE_VIEW_RETRY_INTERVAL_MS;
            } else {
                gotFrame = true;
                pump.consecutiveErrors = 0;
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (frame != null) {
                    mainHandler.post(() -> listener.onLiveViewFrame(frame));
                }
            }
        } catch (Exception error) {
            pump.consecutiveErrors++;
            if (pump.consecutiveErrors >= LIVE_VIEW_MAX_CONSECUTIVE_ERRORS) {
                // 连续失败基本等同于链路故障：停掉循环，把原因交给界面，重连由心跳负责。
                liveViewActive = false;
                String message = error.getMessage() != null ? error.getMessage() : "实时取景中断";
                mainHandler.post(() -> listener.onLiveViewStatus("实时取景中断：" + message));
                return false;
            }
            delay = LIVE_VIEW_RETRY_INTERVAL_MS;
        }

        long now = System.currentTimeMillis();
        if (gotFrame) {
            pump.lastFrameAt = now;
        } else if (liveViewActive && generation == liveViewGeneration
                && now - pump.lastFrameAt >= LIVE_VIEW_STALL_RESTART_MS
                && now - pump.lastRestartAt >= LIVE_VIEW_STALL_RESTART_MS) {
            // 先确认循环仍有效：面板关闭（stopLiveView）会把 liveViewActive 置 false 并作废代际，
            // 但在途的这一帧还要等 operations.liveViewFrame() 返回才走到这里。若不复查就重发
            // 开启序列，它会经 ensureCanonRemoteMode() 把相机**重新拽回遥控态**——那就正好抵消
            // 了 stopLiveView 里刚做完的「交还机身」收尾，表现为「关了面板相机还是不能自由使用」。
            //
            // 一帧都没出：相机可能对开启写入回了 OK 却没真正切到取景输出。先重发开启序列；
            // 仍不出图就再补一次半按唤醒——实测「先拍一张后取景才出画面」，说明取景输出要经过
            // 一次释放周期才真正启动，而半按只测光/对焦、不会拍照。
            pump.lastRestartAt = now;
            pump.lastFrameAt = now;
            pump.stallRestarts++;
            try {
                operations.setLiveViewEnabled(true, liveViewSize);
                if (pump.stallRestarts > 1) operations.wakeForLiveView();
            } catch (Exception ignored) {
                // 重发失败不额外报错：下面的取帧会继续退避重试。
            }
            String hint = pump.stallRestarts > 1
                    ? "实时取景无画面，已尝试半按唤醒相机…"
                    : "实时取景无画面，正在重发开启序列…";
            mainHandler.post(() -> listener.onLiveViewStatus(hint));
        }

        if (!sleepQuietly(delay)) {
            // 退出应用/断开时会打断取景线程，直接结束循环即可。
            return false;
        }
        return liveViewActive && generation == liveViewGeneration;
    }

    /** 睡一会儿；被打断返回 false（取景循环据此退出）。 */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            return false;
        }
    }

    /** 读取某个曝光参数的当前档位；读不到时回调里给出原因（相机可能不推送该值）。 */
    public void readExposure(final CanonExposure.Setting setting) {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onExposureResult(
                        setting, -1, null, "请先连接相机。"));
                return;
            }
            try {
                long raw = operations.readExposure(setting);
                // 读取回的是机型自己的编码值（与写入同一套表），按表定位档位；
                // 极少数机型可能回的是标准读数，再按标准换算兜一次。
                int index = CanonExposure.indexOfValue(setting, (int) raw);
                if (index < 0) index = CanonExposure.matchStandard(setting, raw);
                if (index < 0) {
                    mainHandler.post(() -> listener.onExposureResult(
                            setting, -1, null, "相机回的档位不在可选列表里"));
                    return;
                }
                final int resolved = index;
                String display = CanonExposure.display(
                        setting, CanonExposure.entries(setting).get(resolved).label);
                mainHandler.post(() -> listener.onExposureResult(setting, resolved, display, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "读取失败";
                mainHandler.post(() -> listener.onExposureResult(setting, -1, null, message));
            }
        });
    }

    /** 写入某个曝光参数的档位（{@code index} 为 {@link CanonExposure#entries} 的下标）。 */
    public void writeExposure(final CanonExposure.Setting setting, final int index) {
        List<CanonExposure.Entry> entries = CanonExposure.entries(setting);
        if (index < 0 || index >= entries.size()) return;
        final CanonExposure.Entry entry = entries.get(index);
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onExposureResult(
                        setting, -1, null, "请先连接相机。"));
                return;
            }
            try {
                operations.writeExposure(setting, entry.value);
                String display = CanonExposure.display(setting, entry.label);
                mainHandler.post(() -> listener.onExposureResult(setting, index, display, null));
            } catch (Exception error) {
                String message = error.getMessage() != null ? error.getMessage() : "写入失败";
                // 写入失败必须让用户看见：界面上档位会保持在用户选的值（不回退成「—」），
                // 如果不额外报一句，失败就被静默吞掉了。
                mainHandler.post(() -> listener.onLiveViewStatus(setting.label + "写入失败：" + message));
                mainHandler.post(() -> listener.onExposureResult(setting, -1, null, message));
            }
        });
    }

    /**
     * 触发一次单次自动对焦（Canon EOS {@code 0x9154 EOS_DoAf}）。面板上的「自动对焦」
     * 按钮调用；对焦需要取景已开启，所以只在面板打开、取景运行时才有意义。
     */
    public void autofocus() {
        executor.submit(() -> {
            if (!isConnected()) {
                mainHandler.post(() -> listener.onLiveViewStatus("请先连接相机。"));
                mainHandler.post(listener::onAutofocusFinished);
                return;
            }
            mainHandler.post(() -> listener.onLiveViewStatus("正在自动对焦…"));
            String failure = null;
            try {
                operations.autofocus();
            } catch (Exception error) {
                failure = error.getMessage() != null ? error.getMessage() : "对焦失败";
            }
            final String result = failure;
            mainHandler.post(() -> listener.onLiveViewStatus(
                    result == null ? "自动对焦完成" : "自动对焦失败：" + result));
            mainHandler.post(listener::onAutofocusFinished);
        });
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
            // 实时取景开着时机身把画面独占给 App，此时全按会被拒（实测 0x2019 DeviceBusy）。
            // 触发快门前先退出取景，拍完再恢复；libgphoto2 的内存拍摄路径同样是在
            // 关掉取景输出后才让相机真正释放快门的。
            boolean resumeLiveView = liveViewActive;
            // 恢复取景是否已经完成。快门一响就立刻恢复（见下面），finally 只兜底，
            // 免得「暂停」状态跨过整个取回流程——那期间相机拒绝一切 0x9110 属性写入。
            boolean liveViewResumed = false;
            try {
                if (resumeLiveView) {
                    // 取帧先让开：暂停取景与按快门之间不该再往相机插帧请求。
                    liveViewSuspended = true;
                    listener.onStatus("暂停实时取景以释放快门…");
                    try {
                        operations.pauseLiveViewForCapture(liveViewSize);
                    } catch (Exception ignored) {
                        // 退出取景失败也照拍：真失败会体现在快门响应码里。
                    }
                }
                listener.onStatus("读取拍摄前句柄快照…");
                CameraStorage.Snapshot baseline = operations.snapshot(CAPTURE_BASELINE_ITEMS);
                NewPhotoResolver resolver = new NewPhotoResolver(
                        operations.cameraIdentity(), repository::isImported);
                resolver.recordBaseline(baseline);
                activeResolver = resolver;

                listener.onStatus("触发相机快门…");
                long[] directHandles = operations.capture();

                // 快门已经松开，取景不必再等文件取回：暂停期间相机停在 EVFOutputDevice=0
                // 的状态，此时任何 0x9110 属性写入（快门/光圈/ISO）都会被回 0x2019——
                // 「刚拍完就改参数报忙」就是这么来的。下载要花好几秒，必须先把取景还回去。
                if (resumeLiveView) {
                    liveViewResumed = resumeLiveViewAfterCapture(1) == null;
                }

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
                // 兜底恢复：正常情况下上面「快门一响就恢复」已经做完了，只有那次失败
                // （或快门抛异常没走到那一步）才轮到这里重试一次。失败必须报出来——
                // 相机停在 EVFOutputDevice=0 时不接受任何 0x9110 属性写入。
                if (resumeLiveView && !liveViewResumed && liveViewActive && isConnected()) {
                    String failure = resumeLiveViewAfterCapture(2);
                    if (failure != null) {
                        String message = failure;
                        mainHandler.post(() -> listener.onStatus(
                                "恢复实时取景失败，参数调整可能被相机拒绝：" + message));
                    }
                }
                LiveViewPump pump = activePump;
                if (pump != null) pump.lastFrameAt = System.currentTimeMillis();
                // 恢复序列走完才放开取帧，免得帧请求插在相机切回取景输出的中间。
                liveViewSuspended = false;
                // 「未发现 JPEG」等分支不落盘也不报错，必须靠这条回调让界面恢复按钮。
                mainHandler.post(listener::onCaptureFinished);
            }
        });
    }

    /**
     * 拍摄后把实时取景还回去，失败返回原因（调用方报给用户）。
     *
     * <p>这一步不能静默吞掉：{@code pauseLiveViewForCapture} 会把 {@code EVFOutputDevice}
     * 清成 0，相机停在那个状态时会拒绝一切 {@code 0x9110} 属性写入（快门/光圈/ISO 三档
     * 全报 {@code 0x2019}），必须把「没恢复成功」告诉用户，而不是让他对着「参数写不进去」
     * 猜原因。调用方在快门刚响时只试一次（那时相机在写卡，试多了纯等），全部取回结束后
     * 再兜底多试一轮。
     *
     * @param attempts 最多尝试几次
     */
    private String resumeLiveViewAfterCapture(int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                operations.setLiveViewEnabled(true, liveViewSize);
                return null;
            } catch (Exception error) {
                if (attempt == attempts) {
                    return error.getMessage() != null ? error.getMessage() : "相机拒绝了取景开启";
                }
                sleepQuietly(LIVE_VIEW_ENABLE_BACKOFF_MS);
            }
        }
        return null;
    }

    /** 手动下载选中的相机对象；与拍摄路径一致，仅 JPEG 可下载，RAW/其他格式一律跳过。 */
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
                List<CameraStorage.Item> jpegs = new ArrayList<>();
                List<CameraStorage.Item> skipped = new ArrayList<>();
                for (CameraStorage.Item item : items) {
                    if (item.isJpeg()) jpegs.add(item);
                    else skipped.add(item);
                }
                if (jpegs.isEmpty()) {
                    listener.onStatus("所选对象不是 JPEG（" + names(skipped)
                            + "）；按设置（无 JPEG 不保存）已跳过，未下载。");
                    return;
                }
                if (!skipped.isEmpty()) {
                    listener.onStatus("仅 JPEG 可下载，跳过：" + names(skipped));
                }
                for (CameraStorage.Item item : jpegs) {
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
        // 重连期间相机会退出取景：先停循环，重连成功后由界面重新开启。
        invalidateLiveView();
        reconnecting = true;
        connected = false;
        heartbeat.reset();
        reconnectAttempt = 0;
        mainHandler.removeCallbacks(heartbeatRunnable);
        unregisterWifiNetworkCallback();
        unregisterSpecifierCallback();
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

    /** 写入相机 Wi‑Fi 凭据；SSID 为空表示不改网络，沿用系统已连的那条（connect 前调用）。 */
    public void setCameraWifiCredentials(String ssid, String passphrase) {
        cameraSsid = ssid == null ? "" : ssid.trim();
        cameraPassphrase = passphrase == null ? "" : passphrase;
    }

    /**
     * 是否用 {@link WifiNetworkSpecifier} 精确加入相机 AP（纯函数，便于契约测试）：
     * 仅 SDK 29+ 且填写了非空 SSID 时才走系统对话框，低版本 / 留空回退自动选网。
     */
    static boolean shouldUseSpecifier(int sdkInt, String ssid) {
        return sdkInt >= Build.VERSION_CODES.Q && isValidSsid(ssid);
    }

    /** SSID 是否有效：非 null 且去掉首尾空白后非空。 */
    static boolean isValidSsid(String ssid) {
        return ssid != null && !ssid.trim().isEmpty();
    }

    /**
     * 为当前会话绑定相机 Wi‑Fi 网络，并把 socket 工厂注入传输层。
     * 填了 SSID 且系统支持时优先弹「加入网络」对话框精确加入；用户取消 / 超时 / 密码错
     * 只回退到自动选网，不判失败。两条路径都只在拿到「首选网络」时注入其 SocketFactory，
     * 拿不到就交由传输层走默认路由。
     *
     * <p>在 executor 线程上执行；等待系统对话框会短暂阻塞该线程。
     */
    private void bindWifiNetwork(PtpIpTransport active, long generation, boolean reconnect) {
        if (shouldUseSpecifier(Build.VERSION.SDK_INT, cameraSsid)
                && connectAttemptActive(generation, reconnect)) {
            listener.onStatus("正在加入相机 Wi‑Fi「" + cameraSsid + "」…");
            // 先清掉上一次会话可能残留的回调/闸门，再武装本次的闸门。
            unregisterSpecifierCallback();
            CountDownLatch latch = new CountDownLatch(1);
            specifierResult.set(null);
            specifierLatch = latch;
            mainHandler.post(() -> requestSpecifierNetwork(latch));
            Network joined = awaitSpecifierNetwork(latch, SPECIFIER_TIMEOUT_MS);
            specifierLatch = null;
            if (joined != null) {
                // cameraWifiNetwork 已由 onAvailable 置好；这里只补会话绑定与 socket 工厂。
                sessionNetwork = joined;
                active.setSocketFactory(joined.getSocketFactory());
                return;
            }
            unregisterSpecifierCallback();
            if (!connectAttemptActive(generation, reconnect)) return;
            listener.onStatus("未加入相机 Wi‑Fi，改用系统已连网络…");
        }
        seedCameraWifiNetwork();
        registerWifiNetworkCallback();
        Network network = liveCameraWifiNetwork();
        sessionNetwork = network;
        active.setSocketFactory(network != null ? network.getSocketFactory() : null);
    }

    /** 弹出系统「加入相机 Wi‑Fi」对话框并发起请求；必须在主线程调用。 */
    private void requestSpecifierNetwork(CountDownLatch latch) {
        ConnectivityManager manager = connectivityManager;
        if (manager == null || manualDisconnect) {
            latch.countDown();
            return;
        }
        final long callbackGeneration = connectionGeneration;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                // 只有仍是本次会话、且回调未过期时才认领这个网络；否则让 bindWifiNetwork 走回退。
                if (callbackGeneration == connectionGeneration
                        && specifierNetworkCallback == this) {
                    cameraWifiNetwork = network;
                    sessionNetwork = network;
                    specifierResult.set(network);
                }
                latch.countDown();
            }

            @Override public void onUnavailable() {
                // 用户取消 / 超时 / 密码错：由 bindWifiNetwork 走回退，不在此判失败。
                latch.countDown();
            }

            @Override public void onLost(Network network) {
                // specifier 模式下不注册通用回调，网络丢失只能靠这里感知。
                if (callbackGeneration != connectionGeneration) return;
                mainHandler.post(() -> {
                    Network bound = sessionNetwork != null ? sessionNetwork : cameraWifiNetwork;
                    if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                        cameraWifiNetwork = null;
                    }
                    if (bound == null || !bound.equals(network)) return;
                    if (specifierNetworkCallback == this && connected && !manualDisconnect) {
                        listener.onStatus("相机 Wi‑Fi 网络已丢失。");
                        enterReconnecting();
                    }
                });
            }
        };
        specifierNetworkCallback = callback;
        NetworkRequest request;
        try {
            WifiNetworkSpecifier.Builder specifier =
                    new WifiNetworkSpecifier.Builder().setSsid(cameraSsid);
            // 空密码视为开放网络；密码格式不合法时 setWpa2Passphrase 会抛，交由外层回退。
            if (!cameraPassphrase.isEmpty()) specifier.setWpa2Passphrase(cameraPassphrase);
            request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier.build())
                    .build();
        } catch (Exception ignored) {
            specifierNetworkCallback = null;
            latch.countDown();
            return;
        }
        try {
            // 三参重载自 API 26 起可用：超时后系统回调 onUnavailable，无需自己计时。
            manager.requestNetwork(request, callback, (int) SPECIFIER_TIMEOUT_MS);
        } catch (Exception ignored) {
            specifierNetworkCallback = null;
            latch.countDown();
        }
    }

    /** 等系统对话框结果（executor 线程阻塞，回调在主线程放行）；超时/中断返回 null。 */
    private Network awaitSpecifierNetwork(CountDownLatch latch, long timeoutMillis) {
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return specifierResult.get();
    }

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

    private void unregisterSpecifierCallback() {
        ConnectivityManager.NetworkCallback callback = specifierNetworkCallback;
        specifierNetworkCallback = null;
        if (callback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
        }
        // 可能正有 connectAttempt 阻塞在闸门上：立刻放行，避免断开/退出被拖住。
        CountDownLatch latch = specifierLatch;
        specifierLatch = null;
        if (latch != null) latch.countDown();
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
