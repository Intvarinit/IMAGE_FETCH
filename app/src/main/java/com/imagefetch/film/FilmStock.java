package com.imagefetch.film;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置的胶片/相纸密度曲线。
 *
 * <p>数据来自 spektrafilm（Andrea Volpato）的实测 profile，按 CC BY-SA 4.0 使用：
 * 每个 profile 的 {@code density_curves} 是 256 点的 [{R,G,B}] 密度序列，横轴是
 * {@code log_exposure}（本仓库里所有胶片/相纸都是 -3.0…4.0，步长 0.02745）。
 * 生成脚本把它压成 1/400 精度的整数表以控制体积，这里还原成双精度供模拟器查表。
 *
 * <p>只挑选了 {@code stage = filming} 的胶片：负片带上声明的 {@code target_print}
 * 相纸曲线一起打包，避免运行时再做配对；正片（反转片）没有相纸，直接按幻灯片
 * 密度成像。
 */
public final class FilmStock {
    /** 每条的曲线点数。 */
    public static final int CURVE_POINTS = 256;
    /** 密度整数的缩放：数据生成时按 1/400 存（0.0025 精度）。 */
    static final double DENSITY_SCALE = 400.0;
    /** 相纸曲线的 log 曝光轴（与参考仓库的全部 printing profile 一致）。 */
    static final double PRINT_LOG_MIN = -3.0;
    static final double PRINT_LOG_MAX = 4.0;
    /** 缩略图 LUT 的边长：17³ 约 60 KB，96px 小图足够，误差远小于压缩与缩放本身。 */
    private static final int THUMBNAIL_LUT_SIZE = 17;

    public final String id;
    public final String name;
    /** 品牌（Fujifilm / Kodak / Ilford / CineStill），界面上按它分组。 */
    public final String brand;
    /** {@code negative} 或 {@code positive}（反转片）。 */
    public final String type;
    /** 黑白胶片：三条通道密度相同，相纸按中性灰近似，避免逐通道偏色。 */
    public final boolean monochrome;
    public final String antihalation;
    public final String printName;
    /** 胶片密度曲线的 log 曝光范围（含端点）。 */
    public final double logExposureMin;
    public final double logExposureMax;

    /** [通道][256] 的胶片密度（未缩放）。LUT 胶片为 null。 */
    private final short[][] curve;
    /** [通道][256] 的相纸密度（未缩放）。LUT 胶片为 null。 */
    private final short[][] print;
    /** 内置 3D LUT 的 asset 文件名（assets/luts/ 下）；曲线胶片为 null。 */
    private final String lutFile;
    /** 解析后的 LUT：首次用到时才读，避免打开页面就解析近 1 MB 的数据。 */
    private volatile Lut3D lut;
    /** 缩略图用的低精度 LUT（17³，约 60 KB）：完整表留给主预览，缩略图不占大内存。 */
    private volatile Lut3D thumbnailLut;
    private final Context context;

    private FilmStock(
            Context context,
            String id,
            String name,
            String brand,
            String type,
            boolean monochrome,
            String antihalation,
            String printName,
            double logExposureMin,
            double logExposureMax,
            short[][] curve,
            short[][] print,
            String lutFile) {
        this.context = context;
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.type = type;
        this.monochrome = monochrome;
        this.antihalation = antihalation;
        this.printName = printName;
        this.logExposureMin = logExposureMin;
        this.logExposureMax = logExposureMax;
        this.curve = curve;
        this.print = print;
        this.lutFile = lutFile;
    }

    /** 这条胶片走 3D LUT 管线，而不是内置密度曲线。 */
    public boolean hasLut() {
        return lutFile != null && !lutFile.isEmpty();
    }

    /** 懒加载 3D LUT；asset 读取失败时抛出运行时异常，由预览层提示。 */
    Lut3D lut() {
        if (!hasLut()) return null;
        Lut3D loaded = lut;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (lut == null) {
                lut = Lut3D.load(context, "luts/" + lutFile);
                if (lut == null) {
                    throw new IllegalStateException("LUT 读取失败：" + lutFile);
                }
            }
            return lut;
        }
    }

    /** 缩略图用的降采样 LUT；与完整表分开缓存，避免缩略图把 51 张完整表拉进堆。 */
    Lut3D thumbnailLut() {
        if (!hasLut()) return null;
        Lut3D loaded = thumbnailLut;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (thumbnailLut == null) {
                thumbnailLut = Lut3D.loadReduced(context, "luts/" + lutFile, THUMBNAIL_LUT_SIZE);
                if (thumbnailLut == null) {
                    throw new IllegalStateException("LUT 读取失败：" + lutFile);
                }
            }
            return thumbnailLut;
        }
    }

    /** 正片（反转片）：直接观看，不经过相纸。 */
    public boolean isPositive() {
        return "positive".equals(type);
    }

    /** 去掉品牌前缀的名字，下拉框里品牌已经单独分组，不必重复。 */
    public String shortName() {
        if (!brand.isEmpty() && name.startsWith(brand + " ")) {
            return name.substring(brand.length() + 1);
        }
        return name;
    }

    /** 胶片密度（0 起算的光学密度）。 */
    double density(int channel, int index) {
        return curve[channel][index] / DENSITY_SCALE;
    }

    /** 相纸密度。 */
    double printDensity(int channel, int index) {
        return print[channel][index] / DENSITY_SCALE;
    }

    /** 相纸曲线的 log 曝光轴端点（全部 profile 一致）。 */
    static double printLogMin() {
        return PRINT_LOG_MIN;
    }

    static double printLogMax() {
        return PRINT_LOG_MAX;
    }

    /** 内置数据集合：胶片列表 + 许可与署名文案（导出参数里要一起显示）。 */
    public static final class Catalog {
        public final List<FilmStock> films;
        public final String attribution;
        public final String license;

        Catalog(List<FilmStock> films, String attribution, String license) {
            this.films = Collections.unmodifiableList(films);
            this.attribution = attribution;
            this.license = license;
        }

        /** 按 id 找胶片；找不到返回 null。 */
        public FilmStock find(String id) {
            for (FilmStock film : films) {
                if (film.id.equals(id)) return film;
            }
            return null;
        }
    }

    /** 读取并解析 {@code assets/film_stocks.json}。 */
    public static Catalog load(Context context) throws IOException {
        String json;
        try (InputStream input = context.getAssets().open("film_stocks.json")) {
            // 逐块读而不是依赖 available()：压缩存放的 asset 不保证大小可预知。
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8 * 1024];
            int read;
            while ((read = input.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.getJSONArray("films");
            List<FilmStock> films = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                films.add(parse(context, array.getJSONObject(i)));
            }
            return new Catalog(
                    films,
                    root.optString("attribution", ""),
                    root.optString("license", ""));
        } catch (Exception error) {
            throw new IOException("胶片数据解析失败：" + error.getMessage(), error);
        }
    }

    private static FilmStock parse(Context context, JSONObject object)
            throws org.json.JSONException {
        String lutFile = object.optString("lut", "");
        boolean fromLut = !lutFile.isEmpty();
        return new FilmStock(
                context,
                object.getString("id"),
                object.optString("name", object.getString("id")),
                object.optString("brand", ""),
                object.optString("type", ""),
                object.optBoolean("monochrome", false),
                object.optString("antihalation", ""),
                object.optString("printName", ""),
                fromLut ? 0.0 : object.getDouble("logExposureMin"),
                fromLut ? 0.0 : object.getDouble("logExposureMax"),
                fromLut ? null : parseCurve(object.getJSONArray("curve")),
                fromLut || !object.has("print")
                        ? null : parseCurve(object.getJSONArray("print")),
                fromLut ? lutFile : null);
    }

    /** 把 [[r,g,b] × 256] 展开成 [通道][256]。 */
    private static short[][] parseCurve(JSONArray array) throws org.json.JSONException {
        short[][] result = new short[3][array.length()];
        for (int i = 0; i < array.length(); i++) {
            JSONArray row = array.getJSONArray(i);
            result[0][i] = (short) row.getInt(0);
            result[1][i] = (short) row.getInt(1);
            result[2][i] = (short) row.getInt(2);
        }
        return result;
    }
}
