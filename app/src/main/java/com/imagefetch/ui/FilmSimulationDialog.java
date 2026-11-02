package com.imagefetch.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.imagefetch.CameraSessionCoordinator;
import com.imagefetch.R;
import com.imagefetch.film.EmbeddedJpegExtractor;
import com.imagefetch.film.FilmSimulator;
import com.imagefetch.film.FilmStock;
import com.imagefetch.ptp.CameraStorage;
import com.imagefetch.storage.GalleryExporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「胶片模拟」独立全屏页。
 *
 * <p>数据流：相机照片列表（{@link CameraSessionCoordinator#loadFilmPhotoPage}，JPG + RAW）→
 * 取回照片到缓存（{@link CameraSessionCoordinator#fetchFilmSource}）→ 解码
 * （JPEG 直接解码，RAW 抽取内嵌预览）→ 用胶片密度曲线渲染（{@link FilmSimulator}）→
 * 预览 / 长按对比原图 / 导出到系统相册。
 *
 * <p>渲染与解码都在本页自己的单线程执行器上，只把结果切回主线程替换位图，
 * 避免拖动滑杆时卡住相机 IO 或界面。
 */
public final class FilmSimulationDialog extends Dialog {
    /** 请求码：MainActivity 收到系统图片选择结果后转交给 {@link #onLocalImagePicked(Uri)}。 */
    public static final int REQUEST_PICK_LOCAL_IMAGE = 0x4F01;
    /** 请求码：Android 9 及以下导出相册所需的存储权限，结果转交 {@link #onStoragePermissionResult}。 */
    public static final int REQUEST_WRITE_STORAGE = 0x4F02;

    /** 预览渲染的最长边：手机屏幕上足够，滑杆响应也快。 */
    private static final int PREVIEW_MAX_EDGE = 1100;
    /** 胶片下拉里缩略图的最长边：只用来一眼看出色彩倾向。 */
    private static final int THUMB_MAX_EDGE = 96;
    /** 生成缩略图前先把源图缩到的最长边，避免每种胶片都从大图重采样。 */
    private static final int THUMB_REFERENCE_EDGE = 192;
    /**
     * 解码源图的最长边上限。
     *
     * <p>JPEG 用 BitmapFactory 的 2 次幂采样，4000 万像素原图只能取 1/2（约 3120px），
     * 再往上 1/1 解码单张就要 100MB 以上，手上这台机器会直接 OOM；4096 是能覆盖
     * 6000×4000 以内原图全尺寸、又不会让渲染阶段内存翻倍的上限。
     */
    private static final int MAX_SOURCE_EDGE = 4096;
    /**
     * RAW 只有机身内嵌预览（EOS RP 约 1620×1080）。为了不让成品卡在这个尺寸，
     * 解码后按 2 倍做高质量插值放大；细节不会凭空增加，但导出尺寸翻倍。
     */
    private static final int RAW_UPSCALE = 2;
    /** 内嵌预览小于这个最长边时才放大，避免对本来就大的预览做无意义插值。 */
    private static final int RAW_UPSCALE_THRESHOLD = 3000;
    /** 滑杆改成数值后，停手这么久再重渲染，避免连续触发。 */
    private static final long RENDER_DEBOUNCE_MS = 180L;

    /** 默认参数，用于「重置参数」。 */
    private static final int EXPOSURE_DEFAULT = 30;
    private static final int TEMPERATURE_DEFAULT = 50;
    private static final int TINT_DEFAULT = 50;
    private static final int GRAIN_DEFAULT = 35;
    private static final int HALATION_DEFAULT = 35;

    private final CameraSessionCoordinator coordinator;
    private final FilmSimulator.Settings settings = new FilmSimulator.Settings();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FilmStock.Catalog catalog;
    private String catalogError;

    private ImageView previewView;
    private ProgressBar progressView;
    private TextView statusView;
    private TextView compareHint;
    private TextView paramsView;
    private Spinner stockSpinner;
    private Spinner sourceSpinner;
    private FilmSpinnerAdapter stockAdapter;
    /** 每次换源图递增，迟到的缩略图结果直接丢弃。 */
    private int thumbnailGeneration;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button loadButton;
    private Button exportButton;
    private Button pickLocalButton;
    private SeekBar exposureBar;
    private SeekBar temperatureBar;
    private SeekBar tintBar;
    private SeekBar grainBar;
    private SeekBar halationBar;
    private TextView exposureValue;
    private TextView temperatureValue;
    private TextView tintValue;
    private TextView grainValue;
    private TextView halationValue;

    private final List<CameraStorage.Item> sourceItems = new ArrayList<>();
    private int sourcePageIndex;
    private int sourcePageCount = 1;
    private int sourceTotalCount;
    /** 后面还有没筛查完的对象：图片来源页按批扫描，翻页按钮据此保持可用。 */
    private boolean sourceHasNext;

    /** 解码后的源图（RAW 内嵌预览或相机 JPEG）；null 表示还没取照片。 */
    private Bitmap sourceBitmap;
    /** 当前显示的模拟结果。 */
    private Bitmap previewBitmap;
    private boolean showingOriginal;
    /** 正在下载的照片缓存文件；换下一张或关闭页面时删除。 */
    private File currentSourceFile;
    /** 当前源图的显示名（相机文件名或手机图片名），用于状态栏文案。 */
    private String sourceLabel = "";
    /** 当前源图是否是 RAW 内嵌预览并做了放大，状态栏要如实标注。 */
    private boolean sourceUpscaled;
    /** 渲染代数：滑杆连续变化时丢弃迟到的旧结果。 */
    private int renderGeneration;
    private boolean downloading;
    private boolean decoding;
    private boolean exporting;
    /** 因缺少存储权限而挂起的导出：用户授权后继续。 */
    private boolean pendingExportAfterPermission;
    private boolean released;

    private final Runnable renderRunnable = this::startPreviewRender;

    public FilmSimulationDialog(Context context, CameraSessionCoordinator coordinator) {
        super(context);
        this.coordinator = coordinator;
        try {
            catalog = FilmStock.load(context);
        } catch (Exception error) {
            catalogError = error.getMessage() == null
                    ? context.getString(R.string.film_data_load_failed)
                    : error.getMessage();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_film_simulation);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setDimAmount(0f);
        }

        // targetSdk 35 起窗口会延伸到系统栏下方，给内容让出安全区。
        findViewById(R.id.filmRoot).setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    view.getPaddingLeft(),
                    insets.getSystemWindowInsetTop(),
                    view.getPaddingRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        previewView = findViewById(R.id.filmPreview);
        progressView = findViewById(R.id.filmProgress);
        statusView = findViewById(R.id.filmStatus);
        compareHint = findViewById(R.id.filmCompareHint);
        paramsView = findViewById(R.id.filmParams);
        stockSpinner = findViewById(R.id.filmStockSpinner);
        sourceSpinner = findViewById(R.id.filmSourceSpinner);
        prevPageButton = findViewById(R.id.filmPrevPage);
        nextPageButton = findViewById(R.id.filmNextPage);
        loadButton = findViewById(R.id.filmLoad);
        exportButton = findViewById(R.id.filmExport);
        pickLocalButton = findViewById(R.id.filmPickLocal);
        exposureBar = findViewById(R.id.filmExposureBar);
        temperatureBar = findViewById(R.id.filmTemperatureBar);
        tintBar = findViewById(R.id.filmTintBar);
        grainBar = findViewById(R.id.filmGrainBar);
        halationBar = findViewById(R.id.filmHalationBar);
        exposureValue = findViewById(R.id.filmExposureValue);
        temperatureValue = findViewById(R.id.filmTemperatureValue);
        tintValue = findViewById(R.id.filmTintValue);
        grainValue = findViewById(R.id.filmGrainValue);
        halationValue = findViewById(R.id.filmHalationValue);

        findViewById(R.id.filmClose).setOnClickListener(view -> dismiss());

        setUpStocks();
        setUpSource();
        setUpSliders();

        previewView.setOnTouchListener(this::onPreviewTouch);
        loadButton.setOnClickListener(view -> loadSelectedSource());
        pickLocalButton.setOnClickListener(view -> pickLocalImage());
        exportButton.setOnClickListener(view -> exportSimulation());
        findViewById(R.id.filmReset).setOnClickListener(view -> resetSettings());

        updateSliderLabels();
        refreshSourcePage(0);
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    // ── 界面搭建 ──

    private void setUpStocks() {
        if (catalog == null || catalog.films.isEmpty()) {
            stockSpinner.setEnabled(false);
            statusView.setText(catalogError == null
                    ? getContext().getString(R.string.film_no_catalog)
                    : catalogError);
            return;
        }
        List<String> names = new ArrayList<>(catalog.films.size());
        for (FilmStock film : catalog.films) names.add(film.name);
        stockAdapter = new FilmSpinnerAdapter(getContext(), names);
        stockSpinner.setAdapter(stockAdapter);
        int defaultIndex = Math.max(0, indexOfStock("kodak_portra_400"));
        stockSpinner.setSelection(defaultIndex);
        stockSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (sourceBitmap != null) schedulePreview();
                updateParams(selectedStock());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateParams(selectedStock());
    }

    private int indexOfStock(String id) {
        for (int i = 0; i < catalog.films.size(); i++) {
            if (catalog.films.get(i).id.equals(id)) return i;
        }
        return 0;
    }

    private void setUpSource() {
        sourceSpinner.setAdapter(new FilmSpinnerAdapter(
                getContext(), Collections.singletonList(getContext().getString(
                R.string.film_source_select))));
        prevPageButton.setOnClickListener(view -> refreshSourcePage(sourcePageIndex - 1));
        nextPageButton.setOnClickListener(view -> refreshSourcePage(sourcePageIndex + 1));
        // 列表还没回来时没有可选项，先禁用，避免点了只弹一句提示。
        loadButton.setEnabled(false);
        updatePageButtons();
    }

    private void setUpSliders() {
        exposureBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settings.exposureEv = (progress - EXPOSURE_DEFAULT) / 10.0;
                updateSliderLabels();
                schedulePreview();
            }
        });
        temperatureBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settings.temperature = (progress - TEMPERATURE_DEFAULT) / 50.0;
                updateSliderLabels();
                schedulePreview();
            }
        });
        tintBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settings.tint = (progress - TINT_DEFAULT) / 50.0;
                updateSliderLabels();
                schedulePreview();
            }
        });
        grainBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settings.grain = progress / 100.0;
                updateSliderLabels();
                schedulePreview();
            }
        });
        halationBar.setOnSeekBarChangeListener(new OnSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settings.halation = progress / 100.0;
                updateSliderLabels();
                schedulePreview();
            }
        });
    }

    private void updateSliderLabels() {
        exposureValue.setText(formatEv(settings.exposureEv));
        temperatureValue.setText(formatShift(settings.temperature, "暖", "冷"));
        tintValue.setText(formatShift(settings.tint, "洋红", "绿"));
        grainValue.setText(String.format(Locale.ROOT, "%d%%",
                Math.round(settings.grain * 100)));
        halationValue.setText(String.format(Locale.ROOT, "%d%%",
                Math.round(settings.halation * 100)));
    }

    private static String formatEv(double ev) {
        return String.format(Locale.ROOT, "%+.1f EV", ev);
    }

    private static String formatShift(double value, String positive, String negative) {
        int amount = (int) Math.round(value * 100);
        if (amount == 0) return "0";
        return (amount > 0 ? positive : negative) + " " + Math.abs(amount);
    }

    private void resetSettings() {
        exposureBar.setProgress(EXPOSURE_DEFAULT);
        temperatureBar.setProgress(TEMPERATURE_DEFAULT);
        tintBar.setProgress(TINT_DEFAULT);
        grainBar.setProgress(GRAIN_DEFAULT);
        halationBar.setProgress(HALATION_DEFAULT);
    }

    // ── 相机 RAW 列表 ──

    private void refreshSourcePage(int pageIndex) {
        if (catalog == null) return;
        // 传输/解析进行中不换页：否则下拉框指向新一页、结果却是旧页那一条，界面会对不上。
        if (downloading || decoding || exporting) return;
        if (!coordinator.isConnected()) {
            statusView.setText(R.string.film_status_not_connected);
            loadButton.setEnabled(false);
            return;
        }
        statusView.setText(R.string.film_status_loading_list);
        coordinator.loadFilmPhotoPage(Math.max(0, pageIndex),
                new CameraSessionCoordinator.StoragePageCallback() {
                    @Override
                    public void onLoaded(CameraStorage.Page page) {
                        if (released) return;
                        sourcePageIndex = page.pageIndex;
                        sourcePageCount = page.pageCount();
                        sourceTotalCount = page.totalCount;
                        sourceHasNext = page.hasNext();
                        sourceItems.clear();
                        sourceItems.addAll(page.items);
                        List<String> labels = new ArrayList<>(sourceItems.size());
                        for (CameraStorage.Item item : sourceItems) labels.add(describe(item));
                        if (labels.isEmpty()) {
                            labels.add(getContext().getString(R.string.film_source_empty));
                        }
                        sourceSpinner.setAdapter(new FilmSpinnerAdapter(getContext(), labels));
                        if (!labels.isEmpty()) sourceSpinner.setSelection(0);
                        // 解码/导出进行中即使列表刚好回来，也不能把「下载并模拟」重新点亮。
                        loadButton.setEnabled(!sourceItems.isEmpty()
                                && !downloading && !decoding && !exporting);
                        updatePageButtons();
                        // 解码/导出进度优先：列表迟到的结果不要把它从状态栏顶掉。
                        if (decoding || exporting) return;
                        if (!sourceItems.isEmpty()) {
                            statusView.setText(getContext().getString(R.string.film_page_info,
                                    sourcePageIndex + 1, sourcePageCount, sourceTotalCount));
                        } else if (sourceHasNext) {
                            statusView.setText(R.string.film_status_scanning_more);
                        } else {
                            statusView.setText(R.string.film_status_no_raw);
                        }
                    }

                    @Override
                    public void onError(String message, Throwable error) {
                        if (released) return;
                        sourceItems.clear();
                        sourceHasNext = false;
                        sourceSpinner.setAdapter(new FilmSpinnerAdapter(
                                getContext(), Collections.singletonList(
                                getContext().getString(R.string.film_source_empty))));
                        loadButton.setEnabled(false);
                        updatePageButtons();
                        statusView.setText(message);
                    }
                });
    }

    private void updatePageButtons() {
        boolean idle = !downloading && !decoding && !exporting;
        prevPageButton.setEnabled(idle && sourcePageIndex > 0);
        nextPageButton.setEnabled(idle && sourceHasNext);
    }

    private static String describe(CameraStorage.Item item) {
        String name = item.filename == null ? String.valueOf(item.handle) : item.filename;
        StringBuilder text = new StringBuilder(name);
        if (item.width > 0 && item.height > 0) {
            text.append(" · ").append(item.width).append("×").append(item.height);
        }
        if (item.sizeBytes > 0) {
            text.append(String.format(Locale.ROOT, " · %.1f MB",
                    item.sizeBytes / (1024.0 * 1024.0)));
        }
        return text.toString();
    }

    // ── 取回与解码 ──

    private void loadSelectedSource() {
        // 解码/导出进行中不要叠加新的相机事务，避免两张源图互相覆盖。
        if (downloading || decoding || exporting) return;
        if (!coordinator.isConnected()) {
            statusView.setText(R.string.film_status_not_connected);
            return;
        }
        int index = sourceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= sourceItems.size()) {
            statusView.setText(R.string.film_need_raw);
            return;
        }
        final CameraStorage.Item item = sourceItems.get(index);
        downloading = true;
        loadButton.setEnabled(false);
        exportButton.setEnabled(false);
        pickLocalButton.setEnabled(false);
        sourceSpinner.setEnabled(false);
        updatePageButtons();
        progressView.setVisibility(View.VISIBLE);
        statusView.setText(getContext().getString(R.string.film_status_downloading,
                item.filename, 0));

        coordinator.fetchFilmSource(item, new CameraSessionCoordinator.FilmSourceCallback() {
            @Override
            public void onProgress(long received, long total) {
                if (released) return;
                if (total > 0) {
                    int percent = (int) Math.min(100, received * 100 / total);
                    statusView.setText(getContext().getString(
                            R.string.film_status_downloading, item.filename, percent));
                } else {
                    statusView.setText(getContext().getString(
                            R.string.film_status_downloading_unknown,
                            item.filename,
                            received / (1024.0 * 1024.0)));
                }
            }

            @Override
            public void onReady(File file) {
                if (released) {
                    file.delete();
                    return;
                }
                downloading = false;
                decoding = true;
                updatePageButtons();
                progressView.setVisibility(View.GONE);
                statusView.setText(R.string.film_status_decoding);
                decodeSourceAsync(file, item.filename);
            }

            @Override
            public void onError(String message, Throwable error) {
                if (released) return;
                downloading = false;
                sourceSpinner.setEnabled(true);
                pickLocalButton.setEnabled(true);
                // 之前已经载入过源图时，导出按钮要恢复可用，否则一次失败就再也导不出。
                exportButton.setEnabled(sourceBitmap != null && !sourceBitmap.isRecycled());
                updatePageButtons();
                progressView.setVisibility(View.GONE);
                loadButton.setEnabled(!sourceItems.isEmpty());
                statusView.setText(message);
            }
        });
    }

    /** 在渲染线程里解码相机照片（JPEG 直接解码，RAW 解内嵌预览并放大），再切回主线程替换源图。 */
    private void decodeSourceAsync(final File file, final String label) {
        renderExecutor.execute(() -> {
            String name = file.getName().toLowerCase(Locale.ROOT);
            boolean raw = !(name.endsWith(".jpg") || name.endsWith(".jpeg"));
            Bitmap decoded = decodeSourcePreview(file);
            boolean upscaled = false;
            if (decoded != null && raw
                    && Math.max(decoded.getWidth(), decoded.getHeight()) < RAW_UPSCALE_THRESHOLD) {
                Bitmap bigger = upscale(decoded, RAW_UPSCALE);
                if (bigger != null) {
                    decoded = bigger;
                    upscaled = true;
                }
            }
            final Bitmap result = decoded;
            final boolean up = upscaled;
            handler.post(() -> finishDecoding(result, file, label, up));
        });
    }

    /**
     * 解码收尾（主线程）：换源图、恢复按钮；解码失败时删掉缓存文件并保留上一张源图。
     *
     * @param cacheFile 相机照片的缓存副本；手机图片为 null
     */
    private void finishDecoding(Bitmap decoded, File cacheFile, String label, boolean upscaled) {
        if (released) {
            if (decoded != null) decoded.recycle();
            if (cacheFile != null) cacheFile.delete();
            return;
        }
        decoding = false;
        sourceSpinner.setEnabled(true);
        pickLocalButton.setEnabled(true);
        updatePageButtons();
        progressView.setVisibility(View.GONE);
        if (decoded == null) {
            // 解码失败也要删掉缓存文件（RAW 可能有几十兆），否则会越积越多。
            if (cacheFile != null) cacheFile.delete();
            loadButton.setEnabled(true);
            exportButton.setEnabled(sourceBitmap != null && !sourceBitmap.isRecycled());
            statusView.setText(R.string.film_no_preview);
            return;
        }
        if (sourceBitmap != null) {
            // 长按对比时 ImageView 正显示着旧源图，先摘下来再回收，避免绘制到已回收的位图。
            if (showingOriginal) {
                previewView.setImageDrawable(null);
                showingOriginal = false;
            }
            sourceBitmap.recycle();
        }
        sourceBitmap = decoded;
        if (currentSourceFile != null) currentSourceFile.delete();
        currentSourceFile = cacheFile;
        sourceLabel = label == null ? "" : label;
        sourceUpscaled = upscaled;
        loadButton.setEnabled(true);
        exportButton.setEnabled(true);
        startPreviewRender();
        refreshStockThumbnails();
    }

    /** 按整数倍做一次高质量放大（RAW 内嵌预览只有 1620×1080，靠它把成品尺寸翻倍）。 */
    private static Bitmap upscale(Bitmap source, int factor) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(
                    source, source.getWidth() * factor, source.getHeight() * factor, true);
            if (scaled != source) source.recycle();
            return scaled;
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }

    // ── 手机本地图片 ──

    /** 打开系统文件选择器选一张本地图片（不需要存储权限）。 */
    private void pickLocalImage() {
        if (downloading || decoding || exporting) return;
        Activity activity = findHostActivity(getContext());
        if (activity == null) {
            statusView.setText(R.string.film_status_local_failed);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_TITLE, getContext().getString(R.string.film_pick_local));
        if (launchPicker(activity, intent)) return;
        // 个别 ROM 没有 DocumentsUI：退回普通图片选择（相册/文件管理器都注册了它）。
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        fallback.setType("image/*");
        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!launchPicker(activity, fallback)) {
            statusView.setText(R.string.film_status_local_failed);
        }
    }

    private boolean launchPicker(Activity activity, Intent intent) {
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_LOCAL_IMAGE);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 沿 {@link ContextWrapper#getBaseContext()} 找回宿主 Activity。
     *
     * <p>{@link Dialog#getContext()} 返回的是包了主题的 {@code ContextThemeWrapper}，
     * 它不是 Activity——直接 {@code instanceof Activity} 判断会一直失败，
     * 表现为「从手机选图」点了没反应（或直接报打不开选择器）。
     */
    private static Activity findHostActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == null || base == context) return null;
            context = base;
        }
        return null;
    }

    /** MainActivity 收到系统选择结果后调用：解码本地图片并作为源图。 */
    public void onLocalImagePicked(Uri uri) {
        if (uri == null || released || downloading || decoding || exporting) return;
        final Context appContext = getContext().getApplicationContext();
        decoding = true;
        loadButton.setEnabled(false);
        exportButton.setEnabled(false);
        pickLocalButton.setEnabled(false);
        updatePageButtons();
        progressView.setVisibility(View.VISIBLE);
        statusView.setText(R.string.film_status_local_loading);
        renderExecutor.execute(() -> {
            // 显示名要走 ContentResolver 查询，云盘提供方可能很慢，放后台线程做。
            String label = localDisplayName(uri);
            Bitmap decoded = decodeLocalImage(appContext, uri);
            final String name = label;
            handler.post(() -> finishDecoding(decoded, null, name, false));
        });
    }

    /** 从 SAF 的 content:// URI 解码图片，按 EXIF 摆正；失败返回 null。 */
    private static Bitmap decodeLocalImage(Context context, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) return null;
            int orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input != null) {
                    orientation = new android.media.ExifInterface(input).getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
                // 读不到 EXIF 就按原样显示。
            }
            return applyOrientation(decoded, orientation);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }

    /** 取 SAF 提供的显示名；取不到就用路径末段。 */
    private String localDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContext().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // 某些文档提供方不支持查询，退回路径末段。
        }
        String last = uri.getLastPathSegment();
        return last == null ? getContext().getString(R.string.film_pick_local) : last;
    }

    /** 相机照片解码入口：JPEG 直接解码（按 EXIF 摆正），RAW 取内嵌预览。 */
    private static Bitmap decodeSourcePreview(File file) {
        try {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return decodeJpegFile(file);
            }
            byte[] jpeg = EmbeddedJpegExtractor.extractLargestJpeg(file);
            if (jpeg == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (decoded == null) return null;
            // 内嵌预览同样带 EXIF 方向（竖拍时 Canon 会写 6/8），按它摆正。
            return applyOrientation(decoded, readJpegOrientation(jpeg));
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    /** 从内存里的 JPEG 字节读 EXIF 方向；没有 EXIF 或读失败都按正常方向处理。 */
    private static int readJpegOrientation(byte[] jpeg) {
        try {
            return new android.media.ExifInterface(new java.io.ByteArrayInputStream(jpeg))
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception | OutOfMemoryError ignored) {
            return android.media.ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** 解码相机 JPEG，并按 EXIF 方向摆正（竖拍照片否则会横躺）。 */
    private static Bitmap decodeJpegFile(File file) {
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(path, options);
        if (decoded == null) return null;
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            return applyOrientation(decoded, orientation);
        } catch (Exception | OutOfMemoryError ignored) {
            // 读不到 EXIF 就按原样显示，不影响模拟。
            return decoded;
        }
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                // 5：沿主对角线翻，等于先转 90° 再水平镜像。
                matrix.postRotate(90);
                matrix.postScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                // 7：沿副对角线翻，等于先转 270° 再水平镜像。
                matrix.postRotate(270);
                matrix.postScale(-1f, 1f);
                break;
            default:
                return source;
        }
        Bitmap rotated = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) source.recycle();
        return rotated;
    }

    private static int sampleSize(BitmapFactory.Options bounds) {
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_SOURCE_EDGE) sample *= 2;
        return sample;
    }

    // ── 模拟与对比 ──

    private void schedulePreview() {
        handler.removeCallbacks(renderRunnable);
        handler.postDelayed(renderRunnable, RENDER_DEBOUNCE_MS);
    }

    private void startPreviewRender() {
        if (released || sourceBitmap == null || sourceBitmap.isRecycled()) return;
        FilmStock stock = selectedStock();
        if (stock == null) return;
        final FilmSimulator.Settings snapshot = settings.copy();
        // 固定这一次渲染用的源图：换 RAW 时旧任务不会去读已被替换/回收的新旧混杂状态。
        final Bitmap source = sourceBitmap;
        final int generation = ++renderGeneration;
        // 导出期间不要再把状态栏改回“正在模拟”，否则会和全尺寸进度打架。
        if (!exporting) {
            statusView.setText(getContext().getString(R.string.film_status_rendering, stock.name));
        }

        renderExecutor.execute(() -> {
            Bitmap result;
            try {
                result = source.isRecycled()
                        ? null
                        : FilmSimulator.render(source, stock, snapshot, PREVIEW_MAX_EDGE);
            } catch (Throwable error) {
                result = null;
            }
            final Bitmap rendered = result;
            // 状态栏报的是成品（导出）尺寸，不是屏幕预览的 1100 级缩小图。
            final int outWidth = source.isRecycled() ? 0 : source.getWidth();
            final int outHeight = source.isRecycled() ? 0 : source.getHeight();
            handler.post(() -> {
                if (released || generation != renderGeneration || rendered == null) {
                    if (rendered != null) rendered.recycle();
                    // 只有“当前这一代”渲染失败才报错；被淘汰的旧任务失败不该覆盖新状态。
                    if (rendered == null && !released && generation == renderGeneration) {
                        statusView.setText(R.string.film_render_failed);
                    }
                    return;
                }
                if (previewBitmap != null && previewBitmap != rendered) previewBitmap.recycle();
                previewBitmap = rendered;
                showingOriginal = false;
                previewView.setImageBitmap(rendered);
                compareHint.setVisibility(View.VISIBLE);
                updateParams(stock);
                // 全尺寸导出还没结束时，状态栏保留「正在生成全尺寸成品…」。
                if (!sourceLabel.isEmpty() && !exporting) {
                    statusView.setText(getContext().getString(
                            sourceUpscaled ? R.string.film_status_ready_upscaled : R.string.film_status_ready,
                            sourceLabel, outWidth, outHeight));
                }
            });
        });
    }

    // ── 胶片缩略图 ──

    /**
     * 用当前源图生成每种胶片的缩略图（固定参考参数，方便横向比较色彩倾向）。
     * 在渲染线程里做，结果只接受最新一代，换图时旧结果直接丢弃。
     */
    private void refreshStockThumbnails() {
        if (catalog == null || stockAdapter == null) return;
        final Bitmap source = sourceBitmap;
        if (source == null || source.isRecycled()) return;
        final int generation = ++thumbnailGeneration;
        renderExecutor.execute(() -> {
            List<Bitmap> thumbs = buildStockThumbnails(source);
            handler.post(() -> {
                if (released || generation != thumbnailGeneration) return;
                stockAdapter.setThumbnails(thumbs);
            });
        });
    }

    private List<Bitmap> buildStockThumbnails(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        Bitmap reference = source;
        if (longest > THUMB_REFERENCE_EDGE) {
            float scale = THUMB_REFERENCE_EDGE / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(source,
                    Math.max(1, (int) (source.getWidth() * scale)),
                    Math.max(1, (int) (source.getHeight() * scale)), true);
            if (scaled != null) reference = scaled;
        }
        // 参考参数是固定的「无偏移」：曝光/白平衡/颗粒都不参与，只比较胶片本身。
        FilmSimulator.Settings referenceSettings = new FilmSimulator.Settings();
        referenceSettings.exposureEv = 0;
        referenceSettings.temperature = 0;
        referenceSettings.tint = 0;
        referenceSettings.grain = 0;
        referenceSettings.halation = 0;

        List<Bitmap> thumbs = new ArrayList<>(catalog.films.size());
        for (FilmStock film : catalog.films) {
            thumbs.add(FilmSimulator.render(reference, film, referenceSettings, THUMB_MAX_EDGE));
        }
        if (reference != source) reference.recycle();
        return thumbs;
    }

    private FilmStock selectedStock() {
        if (catalog == null) return null;
        int index = stockSpinner.getSelectedItemPosition();
        if (index < 0 || index >= catalog.films.size()) return null;
        return catalog.films.get(index);
    }

    private void updateParams(FilmStock stock) {
        if (stock == null) return;
        paramsView.setText(getContext().getString(R.string.film_params,
                stock.name,
                stock.printName,
                formatEv(settings.exposureEv),
                formatShift(settings.temperature, "暖", "冷"),
                formatShift(settings.tint, "洋红", "绿"),
                Math.round(settings.grain * 100),
                Math.round(settings.halation * 100)));
    }

    /** 按下显原图、松手回到模拟图；必须消费事件，否则收不到 ACTION_UP，对比如会卡在原图。 */
    private boolean onPreviewTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (previewBitmap != null && sourceBitmap != null) {
                    showingOriginal = true;
                    previewView.setImageBitmap(sourceBitmap);
                    compareHint.setText(R.string.film_release_compare);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (showingOriginal && previewBitmap != null) {
                    showingOriginal = false;
                    previewView.setImageBitmap(previewBitmap);
                }
                compareHint.setText(R.string.film_long_press_compare);
                break;
            default:
                break;
        }
        return true;
    }

    // ── 导出 ──

    private void exportSimulation() {
        if (downloading || decoding || exporting) return;
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            statusView.setText(R.string.film_need_raw);
            return;
        }
        FilmStock stock = selectedStock();
        if (stock == null) return;
        // Android 9 及以下写相册要存储权限：无权限时先申请，授权结果到达后自动续做导出。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && getContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestLegacyStoragePermission();
            return;
        }
        final FilmSimulator.Settings snapshot = settings.copy();
        final Bitmap source = sourceBitmap;
        exporting = true;
        exportButton.setEnabled(false);
        loadButton.setEnabled(false);
        pickLocalButton.setEnabled(false);
        sourceSpinner.setEnabled(false);
        updatePageButtons();
        statusView.setText(R.string.film_status_exporting);

        renderExecutor.execute(() -> {
            Bitmap full = null;
            File temp = null;
            String exportedName = null;
            Throwable failure = null;
            try {
                full = FilmSimulator.render(source, stock, snapshot, 0);
                if (full == null) throw new IOException("渲染失败。");
                temp = new File(getContext().getCacheDir(),
                        "film_export_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!full.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                        throw new IOException("JPEG 编码失败。");
                    }
                }
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                        .format(new Date());
                exportedName = "FILM_" + stock.id + "_" + stamp + ".jpg";
                new GalleryExporter(getContext()).export(temp, exportedName);
            } catch (Exception | OutOfMemoryError error) {
                failure = error;
            } finally {
                if (full != null) full.recycle();
                if (temp != null) temp.delete();
            }

            final String name = exportedName;
            final Throwable error = failure;
            handler.post(() -> {
                if (released) return;
                exporting = false;
                exportButton.setEnabled(true);
                loadButton.setEnabled(sourceBitmap != null);
                pickLocalButton.setEnabled(true);
                sourceSpinner.setEnabled(true);
                updatePageButtons();
                statusView.setText(error == null
                        ? getContext().getString(R.string.film_status_exported,
                                GalleryExporter.RELATIVE_PATH + "/" + name)
                        : getContext().getString(R.string.film_status_export_failed,
                                error.getMessage() == null ? "未知错误" : error.getMessage()));
            });
        });
    }

    /** 申请 Android 9 及以下的存储权限；结果由 MainActivity 转发回 {@link #onStoragePermissionResult}。 */
    private void requestLegacyStoragePermission() {
        Activity activity = findHostActivity(getContext());
        if (activity == null) {
            statusView.setText(R.string.film_need_storage_permission);
            return;
        }
        pendingExportAfterPermission = true;
        try {
            activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
        } catch (Exception error) {
            pendingExportAfterPermission = false;
            statusView.setText(R.string.film_need_storage_permission);
        }
    }

    /** MainActivity 转发存储权限结果：授予后继续刚才挂起的导出，拒绝则明确提示。 */
    public void onStoragePermissionResult(boolean granted) {
        if (released || !pendingExportAfterPermission) return;
        pendingExportAfterPermission = false;
        if (granted) {
            exportSimulation();
        } else {
            statusView.setText(R.string.film_need_storage_permission);
        }
    }

    // ── 释放 ──

    @Override
    public void dismiss() {
        if (released) {
            super.dismiss();
            return;
        }
        released = true;
        renderGeneration++;
        handler.removeCallbacksAndMessages(null);
        renderExecutor.shutdownNow();
        if (previewView != null) previewView.setImageDrawable(null);
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        if (sourceBitmap != null) {
            // 导出线程还持有源图引用时不能回收，交给 GC 处理，否则导出会读到位图已回收的报错。
            if (!exporting) sourceBitmap.recycle();
            sourceBitmap = null;
        }
        if (currentSourceFile != null) {
            currentSourceFile.delete();
            currentSourceFile = null;
        }
        super.dismiss();
    }

    /** 深色页上的下拉框：收起状态用浅字，展开的弹窗仍是系统浅色底，所以只改收起那一路。 */
    private static final class FilmSpinnerAdapter extends ArrayAdapter<String> {
        /** 与条目一一对应的缩略图；null 表示这一路不显示缩略图（来源列表）。 */
        private List<Bitmap> thumbnails;

        FilmSpinnerAdapter(Context context, List<String> labels) {
            super(context, android.R.layout.simple_spinner_item, labels);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        void setThumbnails(List<Bitmap> thumbs) {
            thumbnails = thumbs;
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (hasThumbnail(position)) return buildRow(position, false);
            View view = super.getView(position, convertView, parent);
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                text.setTextColor(0xFFF1F3F7);
                text.setTextSize(14f);
            }
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (hasThumbnail(position)) return buildRow(position, true);
            return super.getDropDownView(position, convertView, parent);
        }

        private boolean hasThumbnail(int position) {
            return thumbnails != null && position >= 0 && position < thumbnails.size()
                    && thumbnails.get(position) != null;
        }

        /** 缩略图行：左边小图，右边名字；收起态白字，下拉态深字（系统浅色弹窗）。 */
        private View buildRow(int position, boolean dropdown) {
            Context context = getContext();
            float density = context.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(density, 8), dp(density, 3), dp(density, 8), dp(density, 3));

            ImageView image = new ImageView(context);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    dp(density, 46), dp(density, 31));
            image.setLayoutParams(imageParams);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(thumbnails.get(position));
            row.addView(image);

            TextView text = new TextView(context);
            text.setText(getItem(position));
            text.setTextSize(dropdown ? 15f : 14f);
            text.setTextColor(dropdown ? 0xFF1F1F1F : 0xFFF1F3F7);
            // 收起态高度只有 40dp，长名称必须单行截断，否则会被裁掉。
            text.setSingleLine(true);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setPadding(dp(density, 8), 0, 0, 0);
            row.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        private static int dp(float density, int value) {
            return (int) (value * density + 0.5f);
        }
    }

    /** 只关心进度变化的 SeekBar 监听。 */
    private abstract static class OnSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
