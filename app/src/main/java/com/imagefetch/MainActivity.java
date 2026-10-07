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
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import com.imagefetch.ptp.CanonExposure;
import com.imagefetch.ptp.LiveViewSize;
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
    private static final int REQUEST_NOTIFICATIONS = 0x71;
    private static final int REQUEST_NEARBY_WIFI = 0x72;
    private static final int REQUEST_WIFI_PICKER = 0x73;

    private static final String PREFS_NAME = "image_fetch_prefs";
    private static final String PREF_EXPORT_TO_GALLERY = "export_to_gallery";
    private static final String PREF_PAGE_SIZE = "page_size";
    private static final String PREF_JPEG_ONLY = "jpeg_only";
    private static final String PREF_CAMERA_SSID = "camera_ssid";
    private static final String PREF_CAMERA_PASSWORD = "camera_password";
    private static final String PREF_LIVE_VIEW_SIZE = "live_view_size";
    private static final int[] PAGE_SIZE_OPTIONS = {20, 50, 100};
    private static final int DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

    private CameraSessionCoordinator coordinator;
    private SharedPreferences preferences;
    private EditText hostField;
    private EditText portField;
    /**
     * 相机 Wi‑Fi 凭据只持久化、不在界面上显示：用户到系统 Wi‑Fi 页面自己选网，
     * 名称回来后自动记下。为空表示不改网络，仍用系统已连上的那条。
     */
    private Button pickWifiButton;
    /** 是否刚跳去过系统 Wi‑Fi 设置：回来时据此做一次名称回填并给出提示。 */
    private boolean wifiPickerLaunched;
    /** 因缺「定位」权限而暂缓的「选择 Wi‑Fi」跳转，授权回调后补发。 */
    private boolean pendingWifiPicker;
    /** 因缺「附近的设备」权限而暂缓的 Wi‑Fi 连接，授权回调后补发。 */
    private boolean pendingWifiConnect;
    /** 本次会话是否由 Wi‑Fi 发起（连接成功后据此反写 Wi‑Fi 名称；USB 会话与本机 Wi‑Fi 无关）。 */
    private boolean wifiSession;
    private Button connectButton;
    private Button connectUsbButton;
    private Button captureButton;
    private Button refreshButton;
    private Button downloadButton;
    private Button deleteButton;
    private CheckBox exportToGalleryBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button fullScanButton;
    private Spinner pageSizeSpinner;
    private Spinner formatFilterSpinner;
    private TextView pageInfoView;
    private TextView outputDirView;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView selectionCountView;
    private GridView photoGrid;
    private PhotoGridAdapter gridAdapter;

    /** 遥控拍摄面板（点「拍摄并取回」打开）；关闭后置空。 */
    private AlertDialog captureDialog;
    private ImageView liveViewImage;
    private TextView liveViewStatusView;
    private Button captureShootButton;
    /** 面板上的「自动对焦」按钮；对焦进行中禁用，结束后恢复。关闭面板后置空。 */
    private Button captureAutofocusButton;
    /** 取景画面尺寸下拉框（相机端推流分辨率）；面板关闭后置空。 */
    private Spinner liveViewSizeSpinner;
    /** 对焦方式（0xD1BA）下拉框；面板关闭后置空。 */
    private Spinner afModeSpinner;
    /** 对焦方式下拉框各项对应的机上编码值（与选项同序）；未读到时为 null。 */
    private int[] afModeValues;
    /**
     * 下拉框当前由程序设定的对焦方式编码值。选中回调可能是异步到达的（setAdapter/setSelection
     * 之后才触发），用它把「程序填充」与「用户点选」区分开——否则刚读回来的当前值会被当成
     * 用户选择又写回相机。
     */
    private int lastAppliedAfValue;
    private final List<ExposureRow> exposureRows = new ArrayList<>();
    /** 当前显示的取景帧；换帧后延迟回收，避免渲染线程仍在读它。 */
    private Bitmap liveViewBitmap;
    /** 拍摄取回进行中：面板上的快门按钮据此防重复触发。 */
    private boolean captureRunning;
    /** 曝光写入的合并窗口：连点 −/+ 只把最终值发给相机一次。 */
    private static final long EXPOSURE_WRITE_DEBOUNCE_MS = 250L;
    private final Handler deferredRecycle = new Handler(Looper.getMainLooper());
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());

    /** 当前页序号（从 0 开始）与是否处于“完整扫描”模式。 */
    private int currentPage;
    private boolean fullScanMode;
    /** 图片栏是否只显示 JPG（默认开：只有 JPG 能下载，RAW 显示出来也没法用）。
     *  同时决定分页范围——「仅 JPG」时每页就是 {@code pageSize} 张 JPG。 */
    private boolean jpegOnly = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostField = findViewById(R.id.host);
        portField = findViewById(R.id.port);
        pickWifiButton = findViewById(R.id.pickWifi);
        connectButton = findViewById(R.id.connect);
        connectUsbButton = findViewById(R.id.connectUsb);
        captureButton = findViewById(R.id.capture);
        refreshButton = findViewById(R.id.refresh);
        downloadButton = findViewById(R.id.download);
        deleteButton = findViewById(R.id.delete);
        exportToGalleryBox = findViewById(R.id.exportToGallery);
        prevPageButton = findViewById(R.id.prevPage);
        nextPageButton = findViewById(R.id.nextPage);
        fullScanButton = findViewById(R.id.fullScan);
        pageSizeSpinner = findViewById(R.id.pageSize);
        formatFilterSpinner = findViewById(R.id.formatFilter);
        pageInfoView = findViewById(R.id.pageInfo);
        progressBar = findViewById(R.id.progress);
        statusView = findViewById(R.id.status);
        selectionCountView = findViewById(R.id.selectionCount);
        photoGrid = findViewById(R.id.grid);

        gridAdapter = new PhotoGridAdapter(this);
        gridAdapter.setSelectionListener(count -> selectionCountView.setText(
                count == 0 ? getString(R.string.selected_count) : "已选 " + count + " 张"));
        photoGrid.setAdapter(gridAdapter);
        // 点击缩略图放大预览；选中改由缩略图右上角的指示点完成（不再需要长按）。
        photoGrid.setOnItemClickListener(this::onGridItemClick);

        coordinator = new CameraSessionCoordinator(
                this, this::ensureUsbPermission, this);

        outputDirView = findViewById(R.id.outputDir);

        // 开关与每页张数在本次启动前先恢复，再挂监听（避免恢复动作自身触发写盘）。
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pickWifiButton.setOnClickListener(view -> openWifiPicker());
        boolean export = preferences.getBoolean(PREF_EXPORT_TO_GALLERY, true);
        int configuredPageSize = preferences.getInt(PREF_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        setUpPageSizeSpinner(configuredPageSize);
        setUpFormatFilterSpinner(preferences.getBoolean(PREF_JPEG_ONLY, true));

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
            connectWifi(false);
        });

        connectUsbButton.setOnClickListener(view -> {
            if (coordinator.isConnected()) {
                coordinator.disconnect();
                return;
            }
            setBusy(true);
            updateConnectButton(false);
            wifiSession = false;
            coordinator.connect(CameraSessionCoordinator.TransportKind.USB, "", 0);
        });

        captureButton.setOnClickListener(view -> {
            if (!coordinator.isConnected()) {
                showError("请先连接相机。", null);
                return;
            }
            // 拍摄取回改为在遥控面板里触发：面板同时提供实时取景与快门/光圈/ISO 调整。
            showCapturePanel();
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

        deleteButton.setOnClickListener(view -> confirmDeleteSelected());
    }

    /**
     * 删除所选：删除的是相机存储卡上的文件，删掉不可恢复，因此必须先二次确认。
     * 与下载不同，<b>任意格式</b>都可删（CR3/RAW/视频也能选、也能删）。
     */
    private void confirmDeleteSelected() {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        final List<CameraStorage.Item> selected = gridAdapter.selectedItems();
        if (selected.isEmpty()) {
            showError("请先在图片栏点选要删除的图片。", null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(
                        R.string.delete_confirm_message, selected.size(), previewNames(selected)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> {
                    statusView.setText("正在删除…");
                    setBusy(true);
                    coordinator.deleteObjects(selected);
                })
                .show();
    }

    /** 确认弹窗里只列前几个文件名，避免选中很多时弹窗被撑爆。 */
    private static String previewNames(List<CameraStorage.Item> items) {
        StringBuilder text = new StringBuilder();
        int limit = Math.min(items.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (text.length() > 0) text.append("\n");
            text.append(items.get(i).filename);
        }
        if (items.size() > limit) {
            text.append("\n… 等共 ").append(items.size()).append(" 个文件");
        }
        return text.toString();
    }

    /** 按当前模式读取指定页；快速分页只读该页，完整扫描首次整卡枚举后内存分页。 */
    private void goToPage(int pageIndex) {
        final int target = Math.max(0, pageIndex);
        setBusy(true);
        statusView.setText("正在读取存储卡…");
        coordinator.loadStoragePage(
                target,
                fullScanMode,
                jpegOnly,
                new CameraSessionCoordinator.StoragePageCallback() {
                    @Override public void onLoaded(CameraStorage.Page page) {
                        setBusy(false);
                        currentPage = page.pageIndex;
                        populate(page);
                        updatePager(page);
                        statusView.setText("读取完成 · " + scopeLabel(page)
                                + (page.complete ? "（完整扫描）" : "（快速分页）"));
                    }

                    @Override public void onError(String message, Throwable error) {
                        setBusy(false);
                        statusView.setText("读取失败 · " + message);
                    }
                });
    }

    /** 分页范围的说明文案：JPG 模式下总数是 JPG 张数，未筛查完时说明还可能更多。 */
    private String scopeLabel(CameraStorage.Page page) {
        if (!jpegOnly) return "共 " + page.totalCount + " 张";
        return page.moreUnscanned
                ? "JPG 已筛出 " + page.totalCount + " 张（还有对象未筛查）"
                : "JPG 共 " + page.totalCount + " 张";
    }

    private void updatePager(CameraStorage.Page page) {
        pageInfoView.setText(String.format(
                Locale.ROOT,
                "第 %d / %d 页 · %s%s",
                page.pageIndex + 1,
                page.pageCount(),
                scopeLabel(page),
                page.complete ? "（完整扫描）" : ""));
        prevPageButton.setEnabled(page.hasPrevious());
        nextPageButton.setEnabled(page.hasNext());
    }

    /** 图片栏点击：放大预览（选中请点缩略图右上角的指示点）。 */
    private void onGridItemClick(AdapterView<?> parent, View view, int position, long id) {
        showPreview((CameraStorage.Item) parent.getItemAtPosition(position));
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
        // 任意格式都可选中（选中后既能删除，JPEG 还能下载），因此全部显示切换按钮。
        toggle.setVisibility(View.VISIBLE);
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

    // ── 遥控拍摄面板（实时取景 + 快门/光圈/ISO） ──

    /**
     * 打开遥控拍摄面板：实时取景预览 + 三档曝光参数 + 「拍摄并取回」。
     * 打开即开取景并读三档当前值；关闭（含链路断开）时停取景并回收最后一帧。
     */
    private void showCapturePanel() {
        if (captureDialog != null) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_capture, null);
        liveViewImage = content.findViewById(R.id.liveViewImage);
        liveViewStatusView = content.findViewById(R.id.liveViewStatus);
        captureShootButton = content.findViewById(R.id.captureShoot);
        captureAutofocusButton = content.findViewById(R.id.captureAutofocus);
        liveViewSizeSpinner = content.findViewById(R.id.liveViewSize);
        afModeSpinner = content.findViewById(R.id.afMode);

        // 取景是 3:2 画面：给黑底定一个固定高度，换帧时弹窗高度才不会跟着抖动。
        // 比例不能太大——预览是弹窗里最高的一块，占满后下面的档位行和拍摄按钮会被挤出
        // 可视区（表现为「按钮被压缩」）。
        ViewGroup.LayoutParams imageParams = liveViewImage.getLayoutParams();
        imageParams.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.28f);
        liveViewImage.setLayoutParams(imageParams);

        // 点按预览只移动对焦框、不触发对焦（用户明确要求：对焦另走「自动对焦」按钮）。
        liveViewImage.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                moveFocusPointAt(event.getX(), event.getY());
            }
            return true;
        });

        exposureRows.clear();
        exposureRows.add(bindExposureRow(
                content, R.id.shutterRow, CanonExposure.Setting.SHUTTER_SPEED));
        exposureRows.add(bindExposureRow(
                content, R.id.apertureRow, CanonExposure.Setting.APERTURE));
        exposureRows.add(bindExposureRow(content, R.id.isoRow, CanonExposure.Setting.ISO));

        captureShootButton.setOnClickListener(view -> shootFromPanel());
        captureAutofocusButton.setOnClickListener(view -> {
            // 对焦要等相机执行完才回，期间禁用按钮避免连点排队。
            captureAutofocusButton.setEnabled(false);
            coordinator.autofocus();
        });
        setUpLiveViewSizeSpinner();
        setUpAfModeSpinner();

        captureDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.capture_panel_title)
                .setView(content)
                .setNegativeButton(R.string.close, null)
                .create();
        captureDialog.setOnDismissListener(ignored -> closeCapturePanel());
        captureDialog.show();

        // 先读三档当前值与对焦方式再开取景：开取景的退避重试最多会占用 executor 数秒，
        // 排在后面会让参数迟迟显示不出来。
        for (ExposureRow row : exposureRows) coordinator.readExposure(row.setting);
        coordinator.readFocusArea();
        coordinator.startLiveView();
    }

    /** 面板收起：停取景、丢掉画面引用（否则整个 Activity 会被这几张位图拖住）。 */
    private void closeCapturePanel() {
        // 还有合并中没发出去的档位调整：立刻补发，别让用户以为改好了其实没生效。
        for (ExposureRow row : exposureRows) flushPendingExposureWrite(row);
        // 拆取景必须排在补发之后：补发档位要趁相机还在遥控态（写属性需要这个状态），
        // 反了的话档位会写进已经收回画面的相机，白写一次。
        // 这里刻意不拆会话：真机（EOS RP）实测「手机能调参、机身同时也能操作」，用户要求
        // 关面板时保持连接，不再重连。
        coordinator.stopLiveView();
        debounceHandler.removeCallbacksAndMessages(null);
        captureDialog = null;
        liveViewImage = null;
        liveViewStatusView = null;
        captureShootButton = null;
        captureAutofocusButton = null;
        liveViewSizeSpinner = null;
        afModeSpinner = null;
        afModeValues = null;
        lastAppliedAfValue = 0;
        exposureRows.clear();
        // 先撤掉延迟回收任务，再回收当前帧；被撤掉的旧帧交给 GC 即可。
        deferredRecycle.removeCallbacksAndMessages(null);
        recycleLiveViewBitmap();
    }

    /** 链路断开/重连时主动收起面板；已经收起则什么都不做。 */
    private void dismissCapturePanel() {
        if (captureDialog != null && captureDialog.isShowing()) captureDialog.dismiss();
    }

    /**
     * 取景画面尺寸下拉框：把档位序号持久化，切换后立刻改相机端推流分辨率
     * （写 0xD1B0 的输出位，取景进行中下一帧生效）。
     */
    private void setUpLiveViewSizeSpinner() {
        LiveViewSize[] sizes = LiveViewSize.values();
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) labels[i] = sizes[i].label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        liveViewSizeSpinner.setAdapter(adapter);
        LiveViewSize current = LiveViewSize.of(preferences.getInt(PREF_LIVE_VIEW_SIZE, 0));
        liveViewSizeSpinner.setSelection(current.ordinal());
        // 先把档位交给协调器，再打开取景：开取景时直接按这个尺寸推流。
        coordinator.setLiveViewSize(current);
        liveViewSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                LiveViewSize size = LiveViewSize.of(position);
                preferences.edit().putInt(PREF_LIVE_VIEW_SIZE, size.ordinal()).apply();
                coordinator.setLiveViewSize(size);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private ExposureRow bindExposureRow(View parent, int rowId, CanonExposure.Setting setting) {
        View row = parent.findViewById(rowId);
        ((TextView) row.findViewById(R.id.rowLabel)).setText(setting.label);
        // 先按全表显示，读到相机允许值列表（onExposureChoices）后再收窄。
        List<CanonExposure.Entry> entries = CanonExposure.entries(setting);
        ExposureRow result = new ExposureRow(setting, row.findViewById(R.id.rowValue), entries);
        // 先按约定默认档显示，读到相机真实值后再覆盖。
        int fallback = CanonExposure.defaultIndex(setting);
        result.index = fallback < 0 ? 0 : fallback;
        result.value.setText(CanonExposure.display(setting, entries.get(result.index).label));
        result.value.setOnClickListener(view -> showExposurePicker(result));
        result.value.setContentDescription(setting.label + " 当前档位，点按可选档");
        row.findViewById(R.id.rowMinus).setOnClickListener(view -> stepExposure(result, -1));
        row.findViewById(R.id.rowPlus).setOnClickListener(view -> stepExposure(result, 1));
        return result;
    }

    /**
     * 对焦方式下拉框：选项来自相机上报的 0xD1BA 允许值列表（读完才填），切换后写回 0xD1BA。
     * 标签按 libgphoto2 的取值名表给出，见 {@link #afModeLabel(int)}。
     */
    private void setUpAfModeSpinner() {
        if (afModeSpinner == null) return;
        // 先放一个占位项，等 onFocusAreaResult 拿到真实列表再替换。
        setAfSpinnerPlaceholder("读取中…");
        afModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (afModeValues == null) return;
                if (position < 0 || position >= afModeValues.length) return;
                int value = afModeValues[position];
                // 与程序当前设定一致说明只是填充/重复选中，不需要写回相机。
                if (value == lastAppliedAfValue) return;
                lastAppliedAfValue = value;
                coordinator.writeFocusArea(value);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 用真实可选值填充下拉框（{@code selectedIndex < 0} 表示选第一项）。 */
    private void setAfSpinnerItems(int[] values, int selectedIndex) {
        if (afModeSpinner == null) return;
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = afModeLabel(values[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        afModeSpinner.setAdapter(adapter);
        int selected = (selectedIndex >= 0 && selectedIndex < values.length) ? selectedIndex : 0;
        afModeSpinner.setSelection(selected);
        lastAppliedAfValue = values[selected];
    }

    /** 下拉框占位/不可用项（此时不设 afModeValues，选中回调会直接忽略）。 */
    private void setAfSpinnerPlaceholder(String label) {
        if (afModeSpinner == null) return;
        afModeValues = null;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new String[]{label});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        afModeSpinner.setAdapter(adapter);
    }

    /**
     * 0xD1BA 取值的中文标签，下标即机型编码。<br>
     * 取值名表取自 libgphoto2 {@code camlibs/ptp2/config.c:2977-2994 canon_eos_afmethod}
     * （0..14，配置项名 {@code "AF Method"}，config.c:12231），括号里保留该表的原始 token
     * 便于对照；中文措辞按佳能机身菜单习惯翻译。M50 Mark II 上报的可选值为
     * {@code {2, 10, 1, 4}}（LiveFace / LiveSpotAF / Live / LiveZone），与机身菜单的四项一致。
     */
    private static final String[] AF_METHOD_LABELS = {
            "快速对焦（Quick）",                        // 0
            "单点自动对焦（Live）",                      // 1
            "面部+追踪（LiveFace）",                     // 2
            "多点自动对焦（LiveMulti）",                 // 3
            "区域自动对焦（LiveZone）",                  // 4
            "单点扩展·十字（LiveSingleExpandCross）",     // 5
            "单点扩展·周围（LiveSingleExpandSurround）",  // 6
            "大区域·横（LiveZoneLargeH）",               // 7
            "大区域·纵（LiveZoneLargeV）",               // 8
            "捕捉对焦（LiveCatchAF）",                   // 9
            "定点自动对焦（LiveSpotAF）",                // 10
            "灵活区域 1（FlexibleZoneAF1）",             // 11
            "灵活区域 2（FlexibleZoneAF2）",             // 12
            "灵活区域 3（FlexibleZoneAF3）",             // 13
            "全区域自动对焦（WholeAreaAF）",             // 14
    };

    /** 对焦方式选项标签；陌生编码如实显示十六进制，不猜名字。 */
    private static String afModeLabel(int value) {
        int code = value & 0xffff;
        if (code < AF_METHOD_LABELS.length) return AF_METHOD_LABELS[code];
        return "未知方式 0x" + String.format(Locale.ROOT, "%04X", code);
    }

    /** −/+ 逐档微调：本地档位立刻推进，写入合并到停止点击之后。 */
    private void stepExposure(ExposureRow row, int delta) {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        int next = row.index + delta;
        if (next < 0 || next >= row.count) return;
        applyExposureSelection(row, next, true);
    }

    /**
     * 选中一个档位。
     * {@code debounce} 为真（−/+ 连点）时，写入合并到 250ms 后的最后一次点击——
     * 跨 6 档只产生 1 次 0x9110 事务，不至于和取景取帧抢同一个单线程队列；
     * 从档位列表直选时立即写入。
     */
    private void applyExposureSelection(ExposureRow row, int index, boolean debounce) {
        if (index < 0 || index >= row.entries.size()) return;
        row.index = index;
        // 用户手选的档位是权威值：立刻钉住，之后任何读取结果都不得把它刷回「—」。
        row.pinned = true;
        row.value.setText(CanonExposure.display(row.setting, row.entries.get(index).label));
        // 上一次还没发出去的合并写入作废：只有最后一次点击算数。
        if (row.pendingWrite != null) {
            debounceHandler.removeCallbacks(row.pendingWrite);
            row.pendingWrite = null;
        }
        if (!debounce) {
            coordinator.writeExposure(row.setting, index);
            return;
        }
        Runnable write = () -> {
            row.pendingWrite = null;
            coordinator.writeExposure(row.setting, row.index);
        };
        row.pendingWrite = write;
        debounceHandler.postDelayed(write, EXPOSURE_WRITE_DEBOUNCE_MS);
    }

    /** 关闭面板前把还没发出去的档位补发掉，否则用户以为改好了其实没生效。 */
    private void flushPendingExposureWrite(ExposureRow row) {
        if (row.pendingWrite == null) return;
        debounceHandler.removeCallbacks(row.pendingWrite);
        row.pendingWrite = null;
        coordinator.writeExposure(row.setting, row.index);
    }

    /** 档位列表直选：跨多档比连点 −/+ 快，且只产生一次写入。 */
    private void showExposurePicker(ExposureRow row) {
        List<CanonExposure.Entry> entries = row.entries;
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = CanonExposure.display(row.setting, entries.get(i).label);
        }
        new AlertDialog.Builder(this)
                .setTitle(row.setting.label)
                .setSingleChoiceItems(labels, row.index, (dialog, which) -> {
                    applyExposureSelection(row, which, false);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 面板上的快门：走与主界面完全相同的一次拍摄取回流程。 */
    private void shootFromPanel() {
        if (!coordinator.isConnected()) {
            showError("请先连接相机。", null);
            return;
        }
        if (captureRunning) return;
        captureRunning = true;
        if (captureShootButton != null) captureShootButton.setEnabled(false);
        if (liveViewStatusView != null) liveViewStatusView.setText(R.string.capture_running);
        progressBar.setProgress(0);
        setBusy(true);
        coordinator.captureAndFetch();
    }

    private ExposureRow rowFor(CanonExposure.Setting setting) {
        for (ExposureRow row : exposureRows) {
            if (row.setting == setting) return row;
        }
        return null;
    }

    private void recycleLiveViewBitmap() {
        Bitmap frame = liveViewBitmap;
        liveViewBitmap = null;
        if (frame != null && !frame.isRecycled()) frame.recycle();
    }

    /** 面板上一行曝光参数：档位是取值表下标，不是机上编码值。 */
    private static final class ExposureRow {
        final CanonExposure.Setting setting;
        final TextView value;
        /**
         * 该档当前可选的取值列表。先按全表显示，收到相机允许值列表（onExposureChoices）后
         * 收窄到机身支持的子集；{@code index} 就是它的下标（与协调器一致）。
         */
        List<CanonExposure.Entry> entries;
        /** 可选档位数，跟随 {@link #entries}；−/+ 的边界判定用它。 */
        int count;
        int index;
        /** 已排期但还没发给相机的写入（−/+ 合并用）；空闲时为 null。 */
        Runnable pendingWrite;
        /**
         * 该档的显示值是否已「钉住」：读到过真实值、或用户手动选过档。
         * 钉住之后，后续读取失败不再把值刷成「—」——否则用户每调一次档，
         * 三个要素就一起变回「—」，看着像调整没生效。
         */
        boolean pinned;

        ExposureRow(
                CanonExposure.Setting setting,
                TextView value,
                List<CanonExposure.Entry> entries) {
            this.setting = setting;
            this.value = value;
            this.entries = entries;
            this.count = entries.size();
        }
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

    /**
     * 格式过滤器：切换的是<b>分页范围</b>（「仅 JPG」时每页就是 pageSize 张 JPG），
     * 所以必须重读相机——本地重渲染只会把已按另一种范围切好的页重新筛一遍，页码对不上。
     */
    private void setUpFormatFilterSpinner(boolean jpegOnlyConfigured) {
        String[] labels = {
            getString(R.string.format_filter_jpeg), getString(R.string.format_filter_all)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatFilterSpinner.setAdapter(adapter);
        jpegOnly = jpegOnlyConfigured;
        formatFilterSpinner.setSelection(jpegOnly ? 0 : 1);
        formatFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                boolean only = position == 0;
                if (only == jpegOnly) return;
                jpegOnly = only;
                preferences.edit().putBoolean(PREF_JPEG_ONLY, only).apply();
                // 分页范围变了：回到第一页重读，页码/总数都按新范围算。
                if (coordinator.isConnected()) goToPage(0);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台：重新武装保活探测（HOLD 三态里 SUSPEND → HOLD）。
        if (coordinator != null) coordinator.resumeHold();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统 Wi‑Fi 设置返回：尽力把刚连上的网络名称回填进输入框；
        // Android 13+ 未授予定位权限时读不到（系统脱敏为 <unknown ssid>），此时给一句提示。
        if (!wifiPickerLaunched) return;
        wifiPickerLaunched = false;
        if (!writeBackCameraSsid() && !coordinator.isConnected()) {
            statusView.setText(R.string.pick_wifi_hint);
        }
    }

    @Override
    protected void onStop() {
        // 退到后台：停心跳探测省电、也避免后台一次网络抖动就误判离线；
        // 链路、WifiLock 与正在进行的传输/拍摄/取景都不受影响。
        if (coordinator != null) coordinator.suspendHold();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // 先收起遥控面板：它持有取景位图，且停取景要在 executor 关闭之前发出。
        dismissCapturePanel();
        deferredRecycle.removeCallbacksAndMessages(null);
        debounceHandler.removeCallbacksAndMessages(null);
        recycleLiveViewBitmap();
        if (coordinator != null) coordinator.shutdown();
        // 退出应用即结束会话，前台服务随之停止。
        CameraConnectionService.stop(this);
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
            // 会话保活（M2）：连接成功即前台化，退到后台/息屏也不会被系统回收。
            requestNotificationPermissionIfNeeded();
            CameraConnectionService.start(
                    this, getString(R.string.session_text_connected, cameraName));
            // 主流程：连上就读卡并显示图片栏第一页。
            fullScanMode = false;
            goToPage(0);
            // Wi‑Fi 会话连上后，把实际所用的网络名称反写进输入框（密码读不回来）。
            if (wifiSession) writeBackCameraSsid();
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            // 链路已断：遥控面板里的取景与参数都没了意义，先收起。
            dismissCapturePanel();
            // 链路已断：清掉图片栏，避免展示已经无法下载/预览的旧缩略图。
            gridAdapter.reset();
            statusView.setText(R.string.status_idle);
            // 手动断开即结束会话，前台服务随之停止。
            CameraConnectionService.stop(this);
        });
    }

    @Override
    public void onReconnecting(int attempt, long delayMillis) {
        runOnUiThread(() -> {
            setBusy(false);
            updateConnectButton(false);
            dismissCapturePanel();
            // 链路丢失即清空图片栏（重连成功后 onConnected 会重新读卡填充第一页）。
            gridAdapter.reset();
            statusView.setText("链路丢失，自动重连第 " + attempt + " 次，"
                    + (delayMillis / 1000) + " 秒后重试…");
            // 重连期间保持前台服务运行：进程一旦被回收，退避重连就再也跑不完了。
            CameraConnectionService.start(
                    this, getString(R.string.session_text_reconnecting, attempt));
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
    public void onDeleteFinished(int deleted, int failed, String failedNames) {
        runOnUiThread(() -> {
            setBusy(false);
            gridAdapter.clearSelection();
            if (deleted > 0) {
                statusView.setText("已删除 " + deleted + " 个文件"
                        + (failed > 0 ? "，失败 " + failed + " 个" : ""));
            }
            if (failed > 0) {
                showError("有 " + failed + " 个文件删除失败：\n" + failedNames, null);
            }
            // 卡内已变：重读当前页，让删掉的文件从列表消失。
            if (deleted > 0 && coordinator.isConnected()) goToPage(currentPage);
        });
    }

    @Override
    public void onLiveViewFrame(Bitmap frame) {
        runOnUiThread(() -> {
            if (liveViewImage == null) {
                // 面板已收起，相机还在推的帧直接丢掉。
                if (!frame.isRecycled()) frame.recycle();
                return;
            }
            Bitmap previous = liveViewBitmap;
            liveViewBitmap = frame;
            liveViewImage.setImageBitmap(frame);
            // 约 10fps、每帧 MB 级：延迟回收上一帧，既不攒垃圾也不打断渲染线程的绘制。
            if (previous != null && !previous.isRecycled()) {
                deferredRecycle.postDelayed(previous::recycle, 300L);
            }
        });
    }

    @Override
    public void onLiveViewStatus(String message) {
        runOnUiThread(() -> {
            // 面板已经收起时，取景状态（含「交还机身」的收尾结果）改发到主界面状态栏——
            // 否则「面板关了但相机还在遥控态」这类提示会落在已经消失的视图上，用户看不到。
            if (liveViewStatusView != null) {
                liveViewStatusView.setText(message);
            } else if (statusView != null) {
                statusView.setText(message);
            }
        });
    }

    @Override
    public void onExposureResult(
            CanonExposure.Setting setting, int index, String display, String error) {
        runOnUiThread(() -> {
            ExposureRow row = rowFor(setting);
            if (row == null) return;
            // 这一档已经有排期中的写入：此时到达的结果只反映相机旧值，
            // 让它盖掉本地选择会把用户刚做的调整静默吞掉。
            if (row.pendingWrite != null) return;
            if (error != null) {
                // 值一旦钉住（读到过真实值、或用户手动选过档）就不再回退成「—」：
                // 读取失败只说明「问不到相机」，不代表用户刚设的值不对。
                // 底层报错（例如 EOS 对标准属性读回 0x2005）不进取景状态栏 —— 那儿是取景状态的
                // 显示位，三档互相覆盖只会留下一条吓人的协议错误。
                if (row.pinned) {
                    row.value.setContentDescription(
                            setting.label + "：" + row.value.getText() + "（本次未能从相机复核）");
                    return;
                }
                row.value.setText(R.string.exposure_unknown);
                row.value.setContentDescription(setting.label + "当前档位未知，可点按手动选择");
                return;
            }
            row.index = index;
            row.pinned = true;
            row.value.setText(display);
        });
    }

    @Override
    public void onExposureChoices(
            CanonExposure.Setting setting, List<CanonExposure.Entry> choices) {
        runOnUiThread(() -> {
            ExposureRow row = rowFor(setting);
            if (row == null || choices == null || choices.isEmpty()) return;
            // 换列表前先记住当前下标指向的「机上编码值」：收窄后下标含义变了，
            // 必须按值重新定位，否则之后的 −/+ 与直选会写到另一个档位。
            int currentValue = (row.index >= 0 && row.index < row.entries.size())
                    ? row.entries.get(row.index).value
                    : -1;
            row.entries = choices;
            row.count = choices.size();
            int mapped = -1;
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).value == currentValue) {
                    mapped = i;
                    break;
                }
            }
            row.index = mapped >= 0 ? mapped : Math.min(Math.max(row.index, 0), row.count - 1);
            // 已有本地选择（用户手选过，或有排期中写入）时只换列表不改显示，
            // 与 onExposureResult 的「不盖掉用户选择」保持一致。
            if (row.pinned || row.pendingWrite != null) return;
            row.value.setText(CanonExposure.display(setting, choices.get(row.index).label));
        });
    }

    @Override
    public void onFocusAreaResult(int[] values, int currentIndex, String error) {
        runOnUiThread(() -> {
            if (afModeSpinner == null) return;
            if (error != null || values == null || values.length == 0) {
                setAfSpinnerPlaceholder("（不可用）");
                return;
            }
            afModeValues = values;
            setAfSpinnerItems(values, currentIndex);
        });
    }

    /**
     * 把预览控件上的点按位置换算成取景帧像素坐标并下发 0x915B，只移动对焦框、不触发对焦。
     *
     * <p>预览用 {@code fitCenter}：画面按比例缩放后居中，控件坐标 ≠ 帧像素坐标。这里把
     * ImageView 的显示矩阵求逆（image→view 反过来），把点映射回帧坐标系；映射后落在帧外的
     * （黑边）直接忽略，避免把对焦框推到画面外。
     */
    private void moveFocusPointAt(float viewX, float viewY) {
        if (liveViewImage == null || liveViewBitmap == null) return;
        Matrix inverse = new Matrix();
        if (!liveViewImage.getImageMatrix().invert(inverse)) return;
        float[] point = {viewX, viewY};
        inverse.mapPoints(point);
        int frameWidth = liveViewBitmap.getWidth();
        int frameHeight = liveViewBitmap.getHeight();
        int x = Math.round(point[0]);
        int y = Math.round(point[1]);
        // 落在图像外（fitCenter 的黑边）时忽略：相机只认画面内的坐标。
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return;
        coordinator.touchAfPosition(x, y);
    }

    @Override
    public void onCaptureFinished() {
        runOnUiThread(() -> {
            captureRunning = false;
            if (captureShootButton != null) captureShootButton.setEnabled(true);
            // 「本次未发现 JPEG」既落盘也不报错，只有这条回调能把按钮恢复成可用。
            setBusy(false);
        });
    }

    @Override
    public void onAutofocusFinished() {
        runOnUiThread(() -> {
            if (captureAutofocusButton != null) captureAutofocusButton.setEnabled(true);
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
        deleteButton.setEnabled(!busy);
        connectUsbButton.setEnabled(!busy);
        fullScanButton.setEnabled(!busy);
        pageSizeSpinner.setEnabled(!busy);
        formatFilterSpinner.setEnabled(!busy);
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
     * Wi‑Fi 连接入口。若填了相机 Wi‑Fi 名称，加入网络需要「附近的设备」运行时权限，
     * 先要权限再连，避免首次必然走到回退路径。
     *
     * @param permissionAlreadyRequested 本次点击是否已经走过一次权限申请（避免
     *     「申请 → 回调 → 再申请」的死循环：用户拒绝后系统不会再弹窗）
     */
    private void connectWifi(boolean permissionAlreadyRequested) {
        String host = hostField.getText().toString().trim();
        int port = parsePort(portField.getText().toString().trim());
        if (host.isEmpty() || port <= 0) {
            showError("请输入有效的相机地址与端口。", null);
            return;
        }
        // 凭据来自「选择 Wi‑Fi」后自动记下的偏好值，界面上不再有输入框。
        String ssid = preferences.getString(PREF_CAMERA_SSID, "");
        String password = preferences.getString(PREF_CAMERA_PASSWORD, "");
        if (!permissionAlreadyRequested && !ssid.isEmpty() && !hasNearbyWifiPermission()) {
            pendingWifiConnect = true;
            requestNearbyWifiPermission();
            return;
        }
        performWifiConnect(host, port, ssid, password);
    }

    /** 真正发起 Wi‑Fi 连接；填了 SSID 时由协调器尝试加入，失败会自行回退自动选网。 */
    private void performWifiConnect(String host, int port, String ssid, String password) {
        coordinator.setCameraWifiCredentials(ssid, password);
        wifiSession = true;
        setBusy(true);
        updateConnectButton(false);
        coordinator.connect(CameraSessionCoordinator.TransportKind.WIFI, host, port);
    }

    /**
     * 打开系统 Wi‑Fi 设置页让用户自己选择相机热点。
     *
     * <p>之所以不走「App 自动读取当前 SSID 反写」：Android 13+ 读 {@link WifiInfo#getSSID()}
     * 仍要求 {@code ACCESS_FINE_LOCATION}，而清单里它被 {@code maxSdkVersion="32"} 限定
     * （13+ 只用 {@code NEARBY_WIFI_DEVICES(neverForLocation)}），必然读到 {@code <unknown ssid>}。
     * 交给系统页面选网既不需要额外权限，选网成功与否也一目了然。
     *
     * <p>这里用完整的 {@link Settings#ACTION_WIFI_SETTINGS} 而不是 API 29+ 的
     * {@code Settings.Panel.ACTION_WIFI} 浮层面板：后者只列最近几条热门网络、且不能滚动，
     * 相机热点通常排在后面，根本选不到。
     */
    private void openWifiPicker() {
        if (hasSsidPermissions()) {
            launchWifiSettings();
            return;
        }
        // 读当前 Wi‑Fi 名称在 13+ 仍要定位权限；先补齐再跳，回来才能把名称钉进输入框。
        pendingWifiPicker = true;
        requestPermissions(ssidPermissions(), REQUEST_WIFI_PICKER);
    }

    /** 跳系统 Wi‑Fi 设置页（完整的列表页，可滚动、能看到全部网络）。 */
    private void launchWifiSettings() {
        wifiPickerLaunched = true;
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception error) {
            wifiPickerLaunched = false;
            showError("无法打开系统 Wi‑Fi 设置页。", error);
        }
    }

    /** 读 SSID 所需权限：定位（14 起为「附近的设备」+定位）；NEARBY_WIFI_DEVICES 只够连网。 */
    private String[] ssidPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasSsidPermissions() {
        for (String permission : ssidPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /**
     * 把「系统当前所连的 Wi‑Fi 名称」记进偏好（尽力而为），下次进来就直接用它。
     *
     * <p>密码无法回填：Android 不提供读回已保存 Wi‑Fi 密码的接口。读不到名称（无定位权限、
     * 非 Wi‑Fi 连接、系统返回 {@code <unknown ssid>}）时保持已记下的名称不动，返回 {@code false}。
     *
     * @return 是否确实读到并写入了名称
     */
    private boolean writeBackCameraSsid() {
        String ssid = connectedWifiSsid();
        if (ssid.isEmpty() || ssid.equals(preferences.getString(PREF_CAMERA_SSID, ""))) return false;
        preferences.edit().putString(PREF_CAMERA_SSID, ssid).apply();
        return true;
    }

    /** 系统当前所连 Wi‑Fi 的名称；读不到时返回空串（原样带引号的系统格式会去掉引号）。 */
    private String connectedWifiSsid() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return "";
            WifiInfo info = manager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.isEmpty() || ssid.startsWith("<")) return "";
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception ignored) {
            // 缺权限/系统限制时（SecurityException 等）静默跳过：这只影响一次便利性反写。
            return "";
        }
    }

    /**
     * 加入相机 Wi‑Fi 所需的运行时权限：API 33+ 是「附近的设备」，
     * 更早的版本是精确位置（清单里已用 maxSdkVersion=32 限定）。
     */
    private static String nearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private boolean hasNearbyWifiPermission() {
        return checkSelfPermission(nearbyWifiPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNearbyWifiPermission() {
        requestPermissions(new String[]{nearbyWifiPermission()}, REQUEST_NEARBY_WIFI);
    }

    /**
     * Android 13+ 需要运行时授权才会显示前台服务通知。被拒不影响服务运行
     * （只是通知不展示），所以这里不阻断任何流程。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
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
        if (requestCode == REQUEST_WIFI_PICKER) {
            pendingWifiPicker = false;
            // 没授权也照跳：选网本身不需要权限，只是回来读不到名称，靠手填。
            launchWifiSettings();
            return;
        }
        if (requestCode == REQUEST_NEARBY_WIFI) {
            if (!pendingWifiConnect) return;
            pendingWifiConnect = false;
            // 未授权也继续连接：协调器会退回到「用系统已连网络」的自动选网路径。
            connectWifi(true);
            return;
        }
        if (requestCode == REQUEST_NOTIFICATIONS) return;
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
