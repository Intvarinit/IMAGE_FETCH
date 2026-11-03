package com.imagefetch.film;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析并应用标准 {@code .cube} 3D LUT。
 *
 * <p>数据按 .cube 规范存储：红通道变化最快，其次是绿，最后是蓝。查表用三线性插值，
 * 并把 8bit 输入的索引与权重预先算好，逐像素只做乘加，避免在渲染循环里做除法和强转。
 *
 * <p>解析结果按 asset 路径缓存在进程内：LUT 文件接近 1 MB，界面反复开关时不必重复读取。
 */
public final class Lut3D {
    /**
     * 已解析的 LUT 缓存：按访问顺序淘汰，最多保留 8 张完整表（约 3.4 MB）。
     *
     * <p>51 款全部浏览一遍会把 33³ 表累积到 22 MB，这里设上限避免给预览和
     * 4096px 源图叠加时带来 OOM 风险；被淘汰的表下次选中时重新解析（几十毫秒，
     * 在渲染线程里做）。缩略图另走 {@link #loadReduced}，不进入这个缓存。
     */
    private static final int CACHE_LIMIT = 8;
    private static final Map<String, Lut3D> CACHE =
            new LinkedHashMap<String, Lut3D>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Lut3D> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private final int size;
    private final float[] table;
    private final double[] domainMin = new double[3];
    private final double[] domainMax = {1.0, 1.0, 1.0};
    /** 每个 8bit 输入对应的低/高格点与插值权重。 */
    private final int[][] lower;
    private final int[][] upper;
    private final float[][] fraction;

    private Lut3D(int size, float[] table) {
        this.size = size;
        this.table = table;
        lower = new int[3][256];
        upper = new int[3][256];
        fraction = new float[3][256];
    }

    /** 读取 assets 下的 .cube；失败返回 null。 */
    public static Lut3D load(Context context, String assetPath) {
        synchronized (CACHE) {
            Lut3D cached = CACHE.get(assetPath);
            if (cached != null) return cached;
            try (InputStream input = context.getAssets().open(assetPath)) {
                Lut3D parsed = parse(input);
                CACHE.put(assetPath, parsed);
                return parsed;
            } catch (IOException | RuntimeException error) {
                return null;
            }
        }
    }

    /**
     * 读取并降采样到不超过 {@code targetSize} 的表，结果不进入静态缓存。
     * 缩略图只需要大致色彩倾向，用 17³（每张约 60 KB）就够，不必常驻完整表。
     */
    static Lut3D loadReduced(Context context, String assetPath, int targetSize) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            Lut3D parsed = parse(input);
            return parsed.size > targetSize ? parsed.downsample(targetSize) : parsed;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** 三线性重采样成更小的表；网格点按 .cube 的红最快、蓝最慢顺序写入。 */
    private Lut3D downsample(int targetSize) {
        float[] reduced = new float[targetSize * targetSize * targetSize * 3];
        double scale = (size - 1) / (double) (targetSize - 1);
        int offset = 0;
        for (int b = 0; b < targetSize; b++) {
            double pb = b * scale;
            int b0 = (int) pb;
            int b1 = Math.min(b0 + 1, size - 1);
            float fb = (float) (pb - b0);
            for (int g = 0; g < targetSize; g++) {
                double pg = g * scale;
                int g0 = (int) pg;
                int g1 = Math.min(g0 + 1, size - 1);
                float fg = (float) (pg - g0);
                for (int r = 0; r < targetSize; r++) {
                    double pr = r * scale;
                    int r0 = (int) pr;
                    int r1 = Math.min(r0 + 1, size - 1);
                    float fr = (float) (pr - r0);
                    int base000 = ((b0 * size + g0) * size + r0) * 3;
                    int base100 = ((b0 * size + g0) * size + r1) * 3;
                    int base010 = ((b0 * size + g1) * size + r0) * 3;
                    int base110 = ((b0 * size + g1) * size + r1) * 3;
                    int base001 = ((b1 * size + g0) * size + r0) * 3;
                    int base101 = ((b1 * size + g0) * size + r1) * 3;
                    int base011 = ((b1 * size + g1) * size + r0) * 3;
                    int base111 = ((b1 * size + g1) * size + r1) * 3;
                    for (int channel = 0; channel < 3; channel++) {
                        float c000 = table[base000 + channel];
                        float c100 = table[base100 + channel];
                        float c010 = table[base010 + channel];
                        float c110 = table[base110 + channel];
                        float c001 = table[base001 + channel];
                        float c101 = table[base101 + channel];
                        float c011 = table[base011 + channel];
                        float c111 = table[base111 + channel];
                        float c00 = c000 * (1.0f - fr) + c100 * fr;
                        float c10 = c010 * (1.0f - fr) + c110 * fr;
                        float c01 = c001 * (1.0f - fr) + c101 * fr;
                        float c11 = c011 * (1.0f - fr) + c111 * fr;
                        float c0 = c00 * (1.0f - fg) + c10 * fg;
                        float c1 = c01 * (1.0f - fg) + c11 * fg;
                        reduced[offset + channel] = c0 * (1.0f - fb) + c1 * fb;
                    }
                    offset += 3;
                }
            }
        }
        Lut3D lut = new Lut3D(targetSize, reduced);
        System.arraycopy(domainMin, 0, lut.domainMin, 0, 3);
        System.arraycopy(domainMax, 0, lut.domainMax, 0, 3);
        lut.buildIndexTables();
        return lut;
    }

    private static Lut3D parse(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        int size = 0;
        double[] domainMin = {0.0, 0.0, 0.0};
        double[] domainMax = {1.0, 1.0, 1.0};
        float[] data = null;
        int count = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            if (line.startsWith("LUT_3D_SIZE")) {
                size = parseInt(line.substring("LUT_3D_SIZE".length()).trim());
                data = new float[size * size * size * 3];
                continue;
            }
            if (line.startsWith("DOMAIN_MIN")) {
                parseDoubles(line.substring("DOMAIN_MIN".length()).trim(), domainMin);
                continue;
            }
            if (line.startsWith("DOMAIN_MAX")) {
                parseDoubles(line.substring("DOMAIN_MAX".length()).trim(), domainMax);
                continue;
            }
            if (line.startsWith("TITLE") || line.startsWith("LUT_1D_SIZE")
                    || line.startsWith("LUT_1D_INPUT_RANGE")) {
                continue;
            }
            if (data == null) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 3) continue;
            if (count >= data.length) {
                throw new IOException("LUT 数据点超过声明的尺寸。");
            }
            data[count++] = parseFloat(parts[0]);
            data[count++] = parseFloat(parts[1]);
            data[count++] = parseFloat(parts[2]);
        }
        if (size <= 1 || data == null || count != data.length) {
            throw new IOException("LUT 数据不完整。");
        }
        Lut3D lut = new Lut3D(size, data);
        System.arraycopy(domainMin, 0, lut.domainMin, 0, 3);
        System.arraycopy(domainMax, 0, lut.domainMax, 0, 3);
        lut.buildIndexTables();
        return lut;
    }

    private void buildIndexTables() {
        for (int channel = 0; channel < 3; channel++) {
            double span = domainMax[channel] - domainMin[channel];
            if (span <= 0.0) span = 1.0;
            for (int value = 0; value < 256; value++) {
                double normalized = value / 255.0;
                double position = (normalized - domainMin[channel]) / span * (size - 1);
                if (position <= 0.0) {
                    lower[channel][value] = 0;
                    upper[channel][value] = Math.min(1, size - 1);
                    fraction[channel][value] = 0.0f;
                    continue;
                }
                if (position >= size - 1) {
                    lower[channel][value] = size - 1;
                    upper[channel][value] = size - 1;
                    fraction[channel][value] = 0.0f;
                    continue;
                }
                int index = (int) position;
                lower[channel][value] = index;
                upper[channel][value] = index + 1;
                fraction[channel][value] = (float) (position - index);
            }
        }
    }

    /**
     * 以 8bit 输入查表，结果写入 {@code out}（0…1 的 sRGB 显示值）。
     * {@code out} 由调用方复用，避免每个像素分配数组。
     */
    public void sample8(int red, int green, int blue, float[] out) {
        int r0 = lower[0][red];
        int g0 = lower[1][green];
        int b0 = lower[2][blue];
        int r1 = upper[0][red];
        int g1 = upper[1][green];
        int b1 = upper[2][blue];
        float fr = fraction[0][red];
        float fg = fraction[1][green];
        float fb = fraction[2][blue];

        int strideR = 3;
        int strideG = size * 3;
        int strideB = size * size * 3;
        int base000 = b0 * strideB + g0 * strideG + r0 * strideR;
        int base100 = b0 * strideB + g0 * strideG + r1 * strideR;
        int base010 = b0 * strideB + g1 * strideG + r0 * strideR;
        int base110 = b0 * strideB + g1 * strideG + r1 * strideR;
        int base001 = b1 * strideB + g0 * strideG + r0 * strideR;
        int base101 = b1 * strideB + g0 * strideG + r1 * strideR;
        int base011 = b1 * strideB + g1 * strideG + r0 * strideR;
        int base111 = b1 * strideB + g1 * strideG + r1 * strideR;

        for (int channel = 0; channel < 3; channel++) {
            float c000 = table[base000 + channel];
            float c100 = table[base100 + channel];
            float c010 = table[base010 + channel];
            float c110 = table[base110 + channel];
            float c001 = table[base001 + channel];
            float c101 = table[base101 + channel];
            float c011 = table[base011 + channel];
            float c111 = table[base111 + channel];

            float c00 = c000 * (1.0f - fr) + c100 * fr;
            float c10 = c010 * (1.0f - fr) + c110 * fr;
            float c01 = c001 * (1.0f - fr) + c101 * fr;
            float c11 = c011 * (1.0f - fr) + c111 * fr;
            float c0 = c00 * (1.0f - fg) + c10 * fg;
            float c1 = c01 * (1.0f - fg) + c11 * fg;
            out[channel] = c0 * (1.0f - fb) + c1 * fb;
        }
    }

    private static int parseInt(String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("LUT 尺寸格式错误。", error);
        }
    }

    private static float parseFloat(String value) throws IOException {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException error) {
            throw new IOException("LUT 数据格式错误。", error);
        }
    }

    private static void parseDoubles(String value, double[] target) {
        String[] parts = value.split("\\s+");
        for (int i = 0; i < target.length && i < parts.length; i++) {
            try {
                target[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException ignored) {
                // 保持默认值，交由点数校验兜底。
            }
        }
    }
}
