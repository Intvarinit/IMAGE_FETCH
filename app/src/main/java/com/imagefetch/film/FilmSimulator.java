package com.imagefetch.film;

import android.graphics.Bitmap;

/**
 * 半物理近似的胶片模拟：用参考仓库 profile 的实测密度曲线做「负片 → 相纸」两段映射，
 * 再叠加颗粒与光晕。不做 81 波长光谱计算，也不做层间成色剂耦合，因此结果与参考引擎
 * 不是逐像素一致，但反差、肩部、色偏与高光溢出这些可感知特征来自真实曲线。
 *
 * <p>管线（全部在 sRGB 8bit 线性化之后进行）：
 * <ol>
 *   <li>sRGB → 线性光，乘白平衡增益与曝光倍率；</li>
 *   <li>线性光 → 胶片 log 曝光，查胶片密度曲线得 Dn；</li>
 *   <li>Dn 反比映射成相纸 log 曝光（密度越大、落到相纸上的光越少），查相纸曲线得 Dp；</li>
 *   <li>10^-Dp 为相纸反射率，以中灰 0.18 归一并截断，再编码回 sRGB；</li>
 *   <li>按中间调权重叠颗粒；高光区做降采样高斯近似的光晕（暖色溢出）。</li>
 * </ol>
 *
 * <p>所有逐像素运算都只查预计算的 LUT，{@code Math.pow} 只在建表时出现，
 * 这样 1600×1000 的预览在图库线程里也能在百毫秒量级完成。
 */
public final class FilmSimulator {
    /** 模拟参数；全部是相对量，界面上用滑杆给。 */
    public static final class Settings {
        /** 曝光补偿，单位 EV（-3…+3）。 */
        public double exposureEv;
        /** 色温偏移（-1…+1，正数偏黄/暖）。 */
        public double temperature;
        /** 色调偏移（-1…+1，正数偏洋红）。 */
        public double tint;
        /** 颗粒强度（0…1）。 */
        public double grain = 0.35;
        /** 光晕强度（0…1）。 */
        public double halation = 0.35;

        public Settings copy() {
            Settings copy = new Settings();
            copy.exposureEv = exposureEv;
            copy.temperature = temperature;
            copy.tint = tint;
            copy.grain = grain;
            copy.halation = halation;
            return copy;
        }
    }

    /** 中间调权重用的中灰线性亮度。 */
    private static final double MID_GRAY = 0.18;
    /** 光晕掩码相对原图的分辨率比例（1/4 边长），再靠多级模糊铺开。 */
    private static final int GLOW_DOWNSCALE = 4;
    /** 反查 sRGB 表的分段数。 */
    private static final int ENCODE_STEPS = 1024;

    /** 按原尺寸渲染。 */
    public static Bitmap render(Bitmap source, FilmStock stock, Settings settings) {
        return render(source, stock, settings, 0);
    }

    /**
     * 渲染一张胶片模拟图。
     *
     * @param maxLongEdge 预览用的最长边上限；&lt;=0 表示按原尺寸（导出用）
     */
    public static Bitmap render(
            Bitmap source, FilmStock stock, Settings settings, int maxLongEdge) {
        if (source == null || stock == null || settings == null) return null;
        Bitmap input = source;
        if (maxLongEdge > 0) {
            int longest = Math.max(source.getWidth(), source.getHeight());
            if (longest > maxLongEdge) {
                float scale = maxLongEdge / (float) longest;
                int width = Math.max(1, (int) (source.getWidth() * scale));
                int height = Math.max(1, (int) (source.getHeight() * scale));
                input = Bitmap.createScaledBitmap(source, width, height, true);
            }
        }

        int width = input.getWidth();
        int height = input.getHeight();
        int[] pixels = new int[width * height];
        input.getPixels(pixels, 0, width, 0, 0, width, height);
        if (input != source) input.recycle();

        Settings tuned = settings.copy();
        clamp(tuned);
        float[][] toneLut = buildToneLut(stock, tuned);
        float[] encodeLut = buildEncodeLut();

        double halationStrength = 0.0;
        float[] glow = null;
        if (tuned.halation > 0.01) {
            glow = buildGlow(pixels, width, height, toneLut);
            // 抗光晕层强的胶片（如 Portra 系列）光晕收敛更快，这里按 profile 的标注打折。
            double filmFactor = "strong".equalsIgnoreCase(stock.antihalation) ? 0.55 : 1.0;
            halationStrength = 0.55 * tuned.halation * filmFactor;
        }

        double grainAmount = 0.10 * tuned.grain;
        int glowWidth = glow == null ? 0 : Math.max(1, width / GLOW_DOWNSCALE);
        int glowHeight = glow == null ? 0 : Math.max(1, height / GLOW_DOWNSCALE);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = pixels[rowOffset + x];
                float red = toneLut[0][(argb >>> 16) & 0xff];
                float green = toneLut[1][(argb >>> 8) & 0xff];
                float blue = toneLut[2][argb & 0xff];

                if (grainAmount > 0.0) {
                    float luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
                    float weight = 4.0f * luminance * (1.0f - luminance);
                    if (weight < 0.0f) weight = 0.0f;
                    float common = hashNoise(x, y, 0x51ed) * 0.7f;
                    float nr = common + hashNoise(x, y, 0x2f1b) * 0.3f;
                    float ng = common + hashNoise(x, y, 0x71a3) * 0.3f;
                    float nb = common + hashNoise(x, y, 0x19c7) * 0.3f;
                    float scale = (float) grainAmount * weight;
                    red *= 1.0f + nr * scale;
                    green *= 1.0f + ng * scale;
                    blue *= 1.0f + nb * scale;
                }

                if (glow != null) {
                    float gx = (x + 0.5f) / GLOW_DOWNSCALE - 0.5f;
                    float gy = (y + 0.5f) / GLOW_DOWNSCALE - 0.5f;
                    float value = sampleGlow(glow, glowWidth, glowHeight, gx, gy);
                    float strength = (float) halationStrength * value;
                    red += strength;
                    green += strength * 0.40f;
                    blue += strength * 0.16f;
                }

                int r = encode(red, encodeLut);
                int g = encode(green, encodeLut);
                int b = encode(blue, encodeLut);
                pixels[rowOffset + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static void clamp(Settings settings) {
        settings.exposureEv = Math.max(-3.0, Math.min(3.0, settings.exposureEv));
        settings.temperature = Math.max(-1.0, Math.min(1.0, settings.temperature));
        settings.tint = Math.max(-1.0, Math.min(1.0, settings.tint));
        settings.grain = Math.max(0.0, Math.min(1.0, settings.grain));
        settings.halation = Math.max(0.0, Math.min(1.0, settings.halation));
    }

    /**
     * 建三条 256 项输入 LUT：输入 8bit 电平 → 打印后的线性反射率。
     * 曝光与白平衡都折进来，因此滑杆变化只需重建这张表。
     *
     * <p>相纸的锚点按 18% 反射率反查，而不是取 logE 轴的几何中点：实测相纸曲线
     * 在轴的中点附近已经进肩部（密度饱和），按轴中点归一会把整张图压成灰雾。
     * 反差系数则用相纸白/黑两端的 logE 跨度除以胶片密度跨度，让每条胶片都把
     * 自己的动态范围铺到相纸的可用范围内。
     */
    private static float[][] buildToneLut(FilmStock stock, Settings settings) {
        double filmMidLog = (stock.logExposureMin + stock.logExposureMax) * 0.5;
        double midPrintDensity = -Math.log10(MID_GRAY);
        double[] printMidLog = new double[3];
        double[] printGain = new double[3];
        double[] filmMidDensity = new double[3];
        int last = FilmStock.CURVE_POINTS - 1;
        for (int channel = 0; channel < 3; channel++) {
            printMidLog[channel] = invertPrintLog(stock, channel, midPrintDensity);
            double printMax = stock.printDensity(channel, last);
            double whiteLog = invertPrintLog(stock, channel, 0.05);
            double blackLog = invertPrintLog(stock, channel,
                    Math.max(whiteLog + 0.05, printMax - 0.05));
            double filmSpan = Math.max(0.1, stock.density(channel, last) - stock.density(channel, 0));
            // 曲线若有轻微非单调/肩部截断，黑白跨度可能极小；给一个下限，避免反差系数变成负数。
            printGain[channel] = Math.max(0.05, (blackLog - whiteLog) / filmSpan);
            filmMidDensity[channel] = sampleFilmDensity(stock, channel, filmMidLog);
        }

        // 暖/冷：正数提红降蓝；洋红/绿：正数提红蓝降绿。
        double temperature = 0.28 * settings.temperature;
        double tint = settings.tint;
        double[] gain = new double[3];
        gain[0] = (1.0 + temperature) * (1.0 + 0.12 * tint);
        gain[1] = 1.0 - 0.20 * tint;
        gain[2] = (1.0 - temperature) * (1.0 + 0.12 * tint);
        double exposureGain = Math.pow(2.0, settings.exposureEv);

        float[][] lut = new float[3][FilmStock.CURVE_POINTS];
        for (int channel = 0; channel < 3; channel++) {
            for (int value = 0; value < FilmStock.CURVE_POINTS; value++) {
                double linear = srgbToLinear(value / 255.0) * gain[channel] * exposureGain;
                double logExposure = Math.log10(Math.max(linear, 1e-6) / MID_GRAY) + filmMidLog;
                double negativeDensity = sampleFilmDensity(stock, channel, logExposure);
                double printLog = printMidLog[channel]
                        + (filmMidDensity[channel] - negativeDensity) * printGain[channel];
                double printDensity = samplePrintDensity(stock, channel, printLog);
                lut[channel][value] = (float) Math.min(1.0, Math.pow(10.0, -printDensity));
            }
        }
        return lut;
    }

    /** 反查相纸 logE：密度达到 targetDensity 处的曝光（曲线单调递增，二分即可）。 */
    private static double invertPrintLog(FilmStock stock, int channel, double targetDensity) {
        double low = FilmStock.printLogMin();
        double high = FilmStock.printLogMax();
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) * 0.5;
            if (samplePrintDensity(stock, channel, mid) < targetDensity) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    /** 线性反射率 → sRGB 8bit，预计算而不是逐像素 pow。 */
    private static float[] buildEncodeLut() {
        float[] lut = new float[ENCODE_STEPS + 1];
        for (int i = 0; i <= ENCODE_STEPS; i++) {
            lut[i] = (float) (255.0 * linearToSrgb(i / (double) ENCODE_STEPS));
        }
        return lut;
    }

    private static int encode(float linear, float[] encodeLut) {
        if (linear <= 0.0f) return 0;
        if (linear >= 1.0f) return 255;
        float position = linear * ENCODE_STEPS;
        int index = (int) position;
        if (index >= ENCODE_STEPS) return 255;
        float fraction = position - index;
        return (int) (encodeLut[index] * (1.0f - fraction) + encodeLut[index + 1] * fraction + 0.5f);
    }

    private static double sampleFilmDensity(FilmStock stock, int channel, double logExposure) {
        return sample(stock, channel, logExposure,
                stock.logExposureMin, stock.logExposureMax, false);
    }

    private static double samplePrintDensity(FilmStock stock, int channel, double logExposure) {
        return sample(stock, channel, logExposure,
                FilmStock.printLogMin(), FilmStock.printLogMax(), true);
    }

    private static double sample(
            FilmStock stock, int channel, double logExposure,
            double min, double max, boolean print) {
        double position = (logExposure - min) / (max - min) * (FilmStock.CURVE_POINTS - 1);
        if (position <= 0.0) return print ? stock.printDensity(channel, 0) : stock.density(channel, 0);
        if (position >= FilmStock.CURVE_POINTS - 1) {
            int last = FilmStock.CURVE_POINTS - 1;
            return print ? stock.printDensity(channel, last) : stock.density(channel, last);
        }
        int index = (int) position;
        double fraction = position - index;
        double low = print ? stock.printDensity(channel, index) : stock.density(channel, index);
        double high = print
                ? stock.printDensity(channel, index + 1)
                : stock.density(channel, index + 1);
        return low * (1.0 - fraction) + high * fraction;
    }

    /**
     * 光晕掩码：取线性亮度里 0.6 以上的高光，按 1/4 边长累积成小图后做三级盒式模糊，
     * 得到从高光向外扩散的暖色光斑。
     */
    private static float[] buildGlow(int[] pixels, int width, int height, float[][] toneLut) {
        int glowWidth = Math.max(1, width / GLOW_DOWNSCALE);
        int glowHeight = Math.max(1, height / GLOW_DOWNSCALE);
        float[] mask = new float[glowWidth * glowHeight];
        for (int y = 0; y < height; y++) {
            int glowRow = Math.min(glowHeight - 1, y / GLOW_DOWNSCALE) * glowWidth;
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = pixels[rowOffset + x];
                float luminance = 0.2126f * toneLut[0][(argb >>> 16) & 0xff]
                        + 0.7152f * toneLut[1][(argb >>> 8) & 0xff]
                        + 0.0722f * toneLut[2][argb & 0xff];
                float value = (luminance - 0.6f) / 0.4f;
                if (value <= 0.0f) continue;
                value = value * value;
                int glowIndex = glowRow + Math.min(glowWidth - 1, x / GLOW_DOWNSCALE);
                if (mask[glowIndex] < value) mask[glowIndex] = value;
            }
        }
        int baseRadius = Math.max(1, Math.round(Math.min(glowWidth, glowHeight) * 0.012f));
        mask = boxBlur(mask, glowWidth, glowHeight, baseRadius);
        mask = boxBlur(mask, glowWidth, glowHeight, baseRadius * 2);
        mask = boxBlur(mask, glowWidth, glowHeight, baseRadius * 4);
        return mask;
    }

    /** 可分离盒式模糊（边缘按最近像素钳制），小图上做三次叠加近似高斯。 */
    private static float[] boxBlur(float[] source, int width, int height, int radius) {
        int span = Math.max(0, radius);
        if (span == 0) return source;
        float[] horizontal = new float[source.length];
        float[] temp = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            float sum = 0.0f;
            for (int i = -span; i <= span; i++) {
                sum += source[row + clampIndex(i, width)];
            }
            float divisor = (float) (span * 2 + 1);
            for (int x = 0; x < width; x++) {
                horizontal[row + x] = sum / divisor;
                sum += source[row + clampIndex(x + span + 1, width)];
                sum -= source[row + clampIndex(x - span, width)];
            }
        }
        for (int x = 0; x < width; x++) {
            float sum = 0.0f;
            for (int i = -span; i <= span; i++) {
                sum += horizontal[clampIndex(i, height) * width + x];
            }
            float divisor = (float) (span * 2 + 1);
            for (int y = 0; y < height; y++) {
                temp[y * width + x] = sum / divisor;
                sum += horizontal[clampIndex(y + span + 1, height) * width + x];
                sum -= horizontal[clampIndex(y - span, height) * width + x];
            }
        }
        return temp;
    }

    private static int clampIndex(int value, int limit) {
        if (value < 0) return 0;
        return value >= limit ? limit - 1 : value;
    }

    private static float sampleGlow(float[] glow, int width, int height, float x, float y) {
        if (x <= 0.0f) x = 0.0f;
        if (y <= 0.0f) y = 0.0f;
        if (x >= width - 1) x = width - 1.0f;
        if (y >= height - 1) y = height - 1.0f;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = x - x0;
        float fy = y - y0;
        float top = glow[y0 * width + x0] * (1.0f - fx) + glow[y0 * width + x1] * fx;
        float bottom = glow[y1 * width + x0] * (1.0f - fx) + glow[y1 * width + x1] * fx;
        return top * (1.0f - fy) + bottom * fy;
    }

    /** 确定性噪声：同一张图重复渲染出同一颗颗粒，滑杆拖动时不会闪。 */
    private static float hashNoise(int x, int y, int seed) {
        int value = x * 374761393 + y * 668265263 + seed;
        value = (value ^ (value >>> 13)) * 1274126177;
        value ^= value >>> 16;
        return ((value & 0xffffff) / (float) 0xffffff) * 2.0f - 1.0f;
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double value) {
        if (value <= 0.0) return 0.0;
        if (value >= 1.0) return 1.0;
        return value <= 0.0031308
                ? value * 12.92
                : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
    }

    private FilmSimulator() {
    }
}
