package com.imagefetch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.storage.GalleryExporter;
import com.imagefetch.ui.PhotoGridAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** IMAGE_FETCH 单页界面：连接（Wi‑Fi/USB）→ 拍摄并取回 → 浏览/预览/手动下载。 */
public final class MainActivity extends Activity implements CameraSessionCoordinator.Listener {
    private static final String USB_PERMISSION_ACTION = "com.imagefetch.USB_PERMISSION";
    private static final long USB_PERMISSION_TIMEOUT_MS = 35_000L;
    private static final int REQUEST_WRITE_STORAGE = 0x70;

    private static final String PREFS_NAME = "image_fetch_prefs";
    private static final String PREF_EXPORT_TO_GALLERY = "export_to_gallery";
    private static final String PREF_PAGE_SIZE = "page_size";
    private static final int[] PAGE_SIZE_OPTIONS = {20, 50, 100};
    private static final int DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

    private CameraSessionCoordinator coordinator;
    private SharedPreferences preferences;
    private EditText hostField;
    private EditText portField;
    private Button connectButton;
    private Button connectUsbButton;
    private Button captureButton;
    private Button refreshButton;
    private Button downloadButton;
    private CheckBox exportToGalleryBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button fullScanButton;
    private Spinner pageSizeSpinner;
    private TextView pageInfoView;
    private TextView outputDirView;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView selectionCountView;
    private GridView photoGrid;
    private PhotoGridAdapter gridAdapter;

    /** 当前页序号（从 0 开始）与是否处于“完整扫描”模式。 */
    private int currentPage;
    private boolean fullScanMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostField = findViewById(R.id.host);
        portField = findViewById(R.id.port);
        connectButton = findViewById(R.id.connect);
        connectUsbButton = findViewById(R.id.connectUsb);
        captureButton = findViewById(R.id.capture);
        refreshButton = findViewById(R.id.refresh);
        downloadButton = findViewById(R.id.download);
        exportToGalleryBox = findViewById(R.id.exportToGallery);
        prevPageButton = findViewById(R.id.prevPage);
        nextPageButton = findViewById(R.id.nextPage);
        fullScanButton = findViewById(R.id.fullScan);
        pageSizeSpinner = findViewById(R.id.pageSize);
        pageInfoView = findViewById(R.id.pageInfo);
        progressBar = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);
        selectionCountView = findViewById(R.id.selectionCount);
        photoGrid = findViewById(R.id.grid);

        gridAdapter = new PhotoGridAdapter(this);
        gridAdapter.setSelectionListener(count -> selectionCountView.setText(
                count == 0 ? getString(R.string.selected_count) : "已选 " + count + " 张"));
        photoGrid.setAdapter(gridAdapter);
        // 点击放大预览；长按切换选中（选中跨页保留，便于一次下载）。
        photoGrid.setOnItemClickListener(this::onGridItemClick);
        photoGrid.setOnItemLongClickListener(this::onGridItemLongClick);

        coordinator = new CameraSessionCoordinator(
                this, this::ensureUsbPermission, this);

        outputDirView = findViewById(R.id.outputDir);

        // 开关与每页张数在本次启动前先恢复，再挂监听（避免恢复动作自身触发写盘）。
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean export = preferences.getBoolean(PREF_EXPORT_TO_GALLERY, true);
        int configuredPageSize = preferences.getInt(PREF_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        setUpPageSizeSpinner(configuredPageSize);

        exportToGalleryBox.setChecked(export);
        coordinator.setExportToGallery(export);
        updateStorageLabels();
        exportToGalleryBox.setOnCheckedChangeListener((view, checked) -> {
            coordinator.setExportToGallery(checked);
            preferences.edit().putBoolean(PREF_EXPORT_TO_GALLERY, checked).apply();
            updateStorageLabels();
            if (checked) requestLegacyStoragePermissionIfNeeded();
        });
        if (export) requestLegacyStoragePermissionIfNeeded();

        prevPageButton.setOnClickListener(view -> goToPage(currentPage - 1));
        nextPageButton.setOnClickListener(view -> goToPage(currentPage + 1));
        fullScanButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            // 完整扫描：整卡枚举后内存分页，作为“快速分页可能漏图”的兜底。
            fullScanMode = true;
            coordinator.invalidateStorageCache();
            goToPage(0);
        });

        connectButton.setOnClickListener(view -> {
            if (coordinator.isConnected()) {
                coordinator.disconnect();
                showReconnectHint();
                return;
            }
            String host = hostField.getText().toString().trim();
            int port = parsePort(portField.getText().toString().trim());
            if (host.isEmpty() || port <= 0) {
                showError("请输入有效的相机地址与端口。", null);
                return;
            }
            setBusy(true);
            updateConnectButton(false);
            coordinator.connect(CameraSessionCoordinator.TransportKind.WIFI, host, port);
        });

        connectUsbButton.setOnClickListener(view -> {
            if (coordinator.isConnected()) {
                coordinator.disconnect();
                return;
            }
            setBusy(true);
            updateConnectButton(false);
            coordinator.connect(CameraSessionCoordinator.TransportKind.USB, "", 0);
        });

        captureButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            progressBar.setProgress(0);
            setBusy(true);
            coordinator.captureAndFetch();
        });

        refreshButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            // 手动刷新：卡内对象可能已变，句柄与完整扫描缓存都要作废。
            coordinator.invalidateStorageCache();
            goToPage(currentPage);
        });

        downloadButton.setOnClickListener(view -> {
            List<CameraStorage.Item> selected = gridAdapter.selectedItems();
            if (selected.isEmpty()) {
                showError("请先在图片栏点选要下载的图片。", null);
                return;
            }
            progressBar.setProgress(0);
            setBusy(true);
            coordinator.download(selected);
        });
    }

    /** 按当前模式读取指定页；快速分页只读该页，完整扫描首次整卡枚举后内存分页。 */
    private void goToPage(int pageIndex) {
        final int target = Math.max(0, pageIndex);
        setBusy(true);
        statusView.setText("正在读取存储卡…");
        coordinator.loadStoragePage(
                target,
                fullScanMode,
                new CameraSessionCoordinator.StoragePageCallback() {
                    @Override public void onLoaded(CameraStorage.Page page) {
                        setBusy(false);
                        currentPage = page.pageIndex;
                        populate(page);
                        updatePager(page);
                        statusView.setText("读取完成 · " + page.totalCount + " 张"
                                + (page.complete ? "（完整扫描）" : "（快速分页）"));
                    }

                    @Override public void onError(String message, Throwable error) {
                        setBusy(false);
                        statusView.setText("读取失败 · " + message);
                    }
                });
    }

    private void updatePager(CameraStorage.Page page) {
        pageInfoView.setText(String.format(
                Locale.ROOT,
                "第 %d / %d 页 · 共 %d 张%s",
                page.pageIndex + 1,
                page.pageCount(),
                page.totalCount,
                page.complete ? "（完整扫描）" : ""));
        prevPageButton.setEnabled(page.hasPrevious());
        nextPageButton.setEnabled(page.hasNext());
    }

    /** 图片栏点击：放大预览。 */
    private void onGridItemClick(AdapterView<?> parent, View view, int position, long id) {
        showPreview((CameraStorage.Item) parent.getItemAtPosition(position));
    }

    /** 图片栏长按：切换选中（选中跨页保留，可跨页勾选后一次下载）。 */
    private boolean onGridItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        gridAdapter.toggle((CameraStorage.Item) parent.getItemAtPosition(position));
        return true;
    }

    /**
     * 放大预览：先立即显示已缓存的缩略图，再异步向相机取原图替换
     * （RAW/视频或取图失败时保持缩略图）。预览图只在弹窗存活期间需要，随弹窗关闭放弃引用。
     */
    private void showPreview(final CameraStorage.Item item) {
        if (item == null) return;
        final View content = getLayoutInflater().inflate(R.layout.dialog_photo_preview, null);
        final ImageView image = content.findViewById(R.id.previewImage);
        final TextView info = content.findViewById(R.id.previewInfo);
        final Button toggle = content.findViewById(R.id.previewToggle);

        Bitmap thumbnail = gridAdapter.thumbnail(item.handle);
        if (thumbnail != null) image.setImageBitmap(thumbnail);
        image.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
        info.setText(describe(item) + "\n" + getString(R.string.preview_loading));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.filename)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        final AtomicBoolean showing = new AtomicBoolean(true);
        dialog.setOnDismissListener(ignored -> showing.set(false));

        updatePreviewToggle(toggle, item);
        toggle.setOnClickListener(view -> {
            gridAdapter.toggle(item);
            updatePreviewToggle(toggle, item);
        });

        dialog.show();
        coordinator.loadPreview(item, (handle, bitmap) -> {
            if (!showing.get()) return;
            if (bitmap == null) {
                info.setText(describe(item) + "\n" + getString(R.string.preview_thumbnail_only));
                return;
            }
            image.setImageBitmap(bitmap);
            info.setText(describe(item));
        });
    }

    private void updatePreviewToggle(Button toggle, CameraStorage.Item item) {
        toggle.setText(gridAdapter.isSelected(item.handle)
                ? R.string.preview_toggle_unselect
                : R.string.preview_toggle_select);
    }

    private static String describe(CameraStorage.Item item) {
        StringBuilder text = new StringBuilder(item.capturedAt);
        if (item.width > 0 && item.height > 0) {
            text.append(" · ").append(item.width).append(" × ").append(item.height);
        }
        if (item.sizeBytes > 0) {
            text.append(String.format(
                    Locale.ROOT, " · %.1f MB", item.sizeBytes / (1024.0 * 1024.0)));
        }
        return text.toString();
    }

    /** 明确区分两处落点：应用内暂存（下载中转，导出后清理）与系统相册可见位置。 */
    private void updateStorageLabels() {
        String album = exportToGalleryBox.isChecked()
                ? GalleryExporter.RELATIVE_PATH
                : getString(R.string.album_dir_disabled);
        outputDirView.setText(getString(R.string.output_dir_label) + "："
                + coordinator.outputDirectory().getAbsolutePath()
                + (exportToGalleryBox.isChecked() ? "（导出成功后自动清理）" : "（导出关闭时保留）")
                + "\n" + getString(R.string.album_dir_label) + "：" + album);
    }

    /** 每页张数选择器：恢复上次选择，切换后立即生效并持久化。 */
    private void setUpPageSizeSpinner(int configured) {
        int index = 0;
        for (int i = 0; i < PAGE_SIZE_OPTIONS.length; i++) {
            if (PAGE_SIZE_OPTIONS[i] == configured) index = i;
        }
        final int selectedIndex = index;
        String[] labels = new String[PAGE_SIZE_OPTIONS.length];
        for (int i = 0; i < PAGE_SIZE_OPTIONS.length; i++) {
            labels[i] = String.valueOf(PAGE_SIZE_OPTIONS[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pageSizeSpinner.setAdapter(adapter);
        pageSizeSpinner.setSelection(selectedIndex);
        coordinator.setPageSize(PAGE_SIZE_OPTIONS[selectedIndex]);
        pageSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                int size = PAGE_SIZE_OPTIONS[position];
                if (size == coordinator.pageSize()) return;
                coordinator.setPageSize(size);
                preferences.edit().putInt(PREF_PAGE_SIZE, size).apply();
                // 页边界随页大小改变：回到第一页，避免停在可能为空的尾页。
                if (coordinator.isConnected()) goToPage(0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (coordinator != null) coordinator.shutdown();
        super.onDestroy();
    }

    // ── 协调器回调（均已切回主线程） ──

    @Override
    public void onStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onConnected(String cameraName) {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(true);
            statusView.setText("已连接 · " + cameraName);
            // 主流程：连上就读卡并显示图片栏第一页。
            fullScanMode = false;
            goToPage(0);
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            // 链路已断：清掉图片栏，避免展示已经无法下载/预览的旧缩略图。
            gridAdapter.reset();
            statusView.setText(R.string.status_idle);
        });
    }

    @Override
    public void onReconnecting(int attempt, long delayMillis) {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            // 链路丢失即清空图片栏（重连成功后 onConnected 会重新读卡填充第一页）。
            gridAdapter.reset();
            statusView.setText("链路丢失，自动重连第 " + attempt + " 次，"
                    + (delayMillis / 1000) + " 秒后重试…");
        });
    }

    @Override
    public void onProgress(long received, long total) {
        runOnUiThread(() -> {
            if (total > 0) {
                progressBar.setProgress((int) Math.min(100, received * 100 / total));
                statusView.setText(String.format(
                        Locale.ROOT, "传输中 %d / %d 字节", received, total));
            } else {
                statusView.setText(String.format(
                        Locale.ROOT, "传输中 %d 字节（长度未知）", received));
            }
        });
    }

    @Override
    public void onFileSaved(File file, long bytes, long elapsedMillis, String note) {
        runOnUiThread(() -> {
            setBusy(false);
            progressBar.setProgress(100);
            // note 必须带上：否则相册导出结果会被这条消息覆盖掉，用户无从判断。
            statusView.setText("已保存 " + file.getName() + " · " + bytes
                    + " 字节 · " + elapsedMillis + " ms" + (note == null ? "" : note));
        });
    }

    @Override
    public void onDownloadFinished() {
        runOnUiThread(() -> {
            setBusy(false);
            // 下载已结束：清掉图片栏里之前的选中状态，避免看起来像还没下载。
            gridAdapter.clearSelection();
        });
    }

    @Override
    public void onError(String message, Throwable error) {
        runOnUiThread(() -> {
            setBusy(false);
            showError(message, error);
        });
    }

    // ── 内部 ──

    /** 填充图片栏，并异步逐张拉取缩略图（与相机事务串行，不会与下载交错）。 */
    private void populate(CameraStorage.Page page) {
        gridAdapter.setItems(page.items);
        // 翻页后回到顶端：否则上一页的滚动位置会带过来，看起来像没换页。
        photoGrid.setSelection(0);
        coordinator.loadThumbnails(
                new ArrayList<>(page.items),
                (handle, jpeg) -> gridAdapter.putThumbnailJpeg(handle, jpeg));
    }

    private void setBusy(boolean busy) {
        captureButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        downloadButton.setEnabled(!busy);
        connectUsbButton.setEnabled(!busy);
        fullScanButton.setEnabled(!busy);
        pageSizeSpinner.setEnabled(!busy);
        if (busy) {
            prevPageButton.setEnabled(false);
            nextPageButton.setEnabled(false);
        }
    }

    private void updateConnectButton(boolean connected) {
        connectButton.setText(connected ? R.string.disconnect : R.string.connect);
        connectUsbButton.setText(connected ? R.string.disconnect : R.string.connect_usb);
    }

    /**
     * USB 首次访问授权（移植自 ZENCHE MainActivity.ensureUsbPermission）：
     * 以 UsbManager.hasPermission 为准，广播只用来提前唤醒等待。
     */
    private boolean ensureUsbPermission(UsbManager manager, UsbDevice device) throws Exception {
        if (manager.hasPermission(device)) return true;
        String permissionAction = USB_PERMISSION_ACTION + "." + device.getDeviceId()
                + "." + UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean granted = new AtomicBoolean(false);
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicReference<RuntimeException> requestError = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!permissionAction.equals(intent.getAction())) return;
                granted.set(manager.hasPermission(device));
                latch.countDown();
            }
        };

        runOnUiThread(() -> {
            try {
                IntentFilter filter = new IntentFilter(permissionAction);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(receiver, filter);
                }
                registered.set(true);
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        this,
                        device.getDeviceId(),
                        new Intent(permissionAction).setPackage(getPackageName()),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(device, permissionIntent);
            } catch (RuntimeException error) {
                requestError.set(error);
                latch.countDown();
            }
        });

        boolean finished = false;
        long deadline = System.currentTimeMillis() + USB_PERMISSION_TIMEOUT_MS;
        while (!finished && System.currentTimeMillis() < deadline) {
            if (manager.hasPermission(device)) {
                granted.set(true);
                break;
            }
            long remaining = deadline - System.currentTimeMillis();
            finished = latch.await(Math.min(250, Math.max(1, remaining)), TimeUnit.MILLISECONDS);
        }
        if (manager.hasPermission(device)) granted.set(true);
        runOnUiThread(() -> {
            if (!registered.get()) return;
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        });
        if (requestError.get() != null) {
            throw new Exception("无法请求 USB 访问权限：" + requestError.get().getMessage());
        }
        return granted.get();
    }

    /**
     * Android 9 及以下导出到相册需要 WRITE_EXTERNAL_STORAGE；
     * Android 10+ 走分区存储，不需要任何存储权限。
     */
    private void requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_STORAGE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) return;
        // 未授权不阻断功能：关闭相册导出，照片仍保存到应用内目录。
        exportToGalleryBox.setChecked(false);
        coordinator.setExportToGallery(false);
        showError("未授予存储权限，已关闭“保存到系统相册”；照片仍会保存到应用内目录。", null);
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /**
     * 断开后的提示：相机的 PTP/IP 连接只保留一个槽位，App 断开后相机不会立刻释放，
     * 期间新连接可能被 InitFail 拒绝。App 现在会自己等相机回收槽位（约 2 分钟内多次退避
     * 重试），因此先让用户直接重连；只有反复失败时才需要去相机侧删连接。
     */
    private void showReconnectHint() {
        new AlertDialog.Builder(this)
                .setTitle("已断开")
                .setMessage("已断开与相机的连接。\n\n"
                        + "再次使用：直接点「连接」即可，App 会自动等待相机释放旧连接槽位并重试"
                        + "（最长约 2 分钟）。\n\n"
                        + "若多次重试仍失败，再在相机上删除（断开）当前连接目标，"
                        + "重新进入「连接智能手机」模式后点「连接」。")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showError(String message, Throwable error) {
        String detail = message == null ? "未知错误" : message;
        if (error != null && error.getCause() != null
                && error.getCause().getMessage() != null) {
            detail += "\n\n" + error.getCause().getMessage();
        }
        new AlertDialog.Builder(this)
                // 带上版本号：便于确认报错出自哪一次构建。
                .setTitle("出错了 · v" + versionName())
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }
}
