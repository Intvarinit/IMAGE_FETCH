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
        /** 先转黑白：任意胶片都可以只保留明暗、颗粒与光晕，输出中性灰。 */
        public boolean monochrome;
        /** 黑白滤镜：0 无、1 黄、2 橙、3 红、4 绿（模拟黑白摄影常用滤镜）。 */
        public int monoFilter;
        /** 暗角强度（0…1）。 */
        public double vignette;
        /** 漏光强度（0…1）。 */
        public double lightLeak;
        /** 整体强度（0…1）：与原图的混合比例，1 为完整胶片效果。 */
        public double strength = 1.0;
        /** 高光调整（-1…+1，正数提亮、负数压暗高光）。 */
        public double highlights;
        /** 阴影调整（-1…+1，正数提亮、负数压暗暗部）。 */
        public double shadows;
        /** 白色调整（-1…+1，作用在最亮端）。 */
        public double whites;
        /** 黑色调整（-1…+1，作用在最暗端）。 */
        public double blacks;
        /** 锐化（0…1）：边缘细节增强量。 */
        public double sharpening;
        /** 去薄雾（-1…+1，正数去雾、负数加雾）。 */
        public double dehaze;

        public Settings copy() {
            Settings copy = new Settings();
            copy.exposureEv = exposureEv;
            copy.temperature = temperature;
            copy.tint = tint;
            copy.grain = grain;
            copy.halation = halation;
            copy.monochrome = monochrome;
            copy.monoFilter = monoFilter;
            copy.vignette = vignette;
            copy.lightLeak = lightLeak;
            copy.strength = strength;
            copy.highlights = highlights;
            copy.shadows = shadows;
            copy.whites = whites;
            copy.blacks = blacks;
            copy.sharpening = sharpening;
            copy.dehaze = dehaze;
            return copy;
        }
    }

    /** 中间调权重用的中灰线性亮度。 */
    private static final double MID_GRAY = 0.18;
    /** 光晕掩码相对原图的分辨率比例（1/4 边长），再靠多级模糊铺开。 */
    private static final int GLOW_DOWNSCALE = 4;
    /** 反查 sRGB 表的分段数。 */
    private static final int ENCODE_STEPS = 1024;
    /** 黑白相纸近似：中性灰相纸的反差与密度上下限（约 grade 2 偏软，暗部不糊、高光不硬截）。 */
    private static final double MONO_PRINT_GAMMA = 1.5;
    private static final double MONO_PRINT_MIN_DENSITY = 0.08;
    private static final double MONO_PRINT_MAX_DENSITY = 2.0;
    /** 黑白滤镜的通道权重：黄/橙/红压暗天空，绿色更适合肤色与植被。 */
    private static final float[][] MONO_FILTER_WEIGHTS = {
            {0.2126f, 0.7152f, 0.0722f},
            {0.40f, 0.48f, 0.12f},
            {0.58f, 0.37f, 0.05f},
            {0.82f, 0.16f, 0.02f},
            {0.12f, 0.72f, 0.16f},
    };
    /** 颗粒噪声贴图边长（2 的幂，取模即可平铺）；比逐像素哈希更接近银盐的簇状颗粒。 */
    private static final int GRAIN_TILE_SIZE = 512;
    private static final int GRAIN_TILE_MASK = GRAIN_TILE_SIZE - 1;
    /** 颗粒贴图懒加载：只建一次，所有渲染共用。 */
    private static volatile float[] grainTile;
    /** 导出时分条带处理的行数，显著降低大图内存峰值。 */
    private static final int STRIP_ROWS = 256;

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
        return render(source, stock, settings, maxLongEdge, null);
    }

    /**
     * 缩略图渲染：LUT 胶片改用 17³ 降采样表。96px 的小图看不出 33³ 与 17³ 的差别，
     * 但整页缩略图因此不必把 51 张完整表都读进内存。
     */
    public static Bitmap renderThumbnail(
            Bitmap source, FilmStock stock, Settings settings, int maxLongEdge) {
        return render(source, stock, settings, maxLongEdge,
                stock != null && stock.hasLut() ? stock.thumbnailLut() : null);
    }

    private static Bitmap render(
            Bitmap source, FilmStock stock, Settings settings, int maxLongEdge, Lut3D lutOverride) {
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

        Settings tuned = settings.copy();
        clamp(tuned);
        Lut3D lut = lutOverride != null ? lutOverride
                : (stock.hasLut() ? stock.lut() : null);
        float[][] toneLut = lut == null ? buildToneLut(stock, tuned) : null;
        int[][] adjustLut = lut == null ? null : buildAdjustLut(tuned);
        float[] decodeLut = lut == null ? null : buildDecodeLut();
        float[] lutOut = lut == null ? null : new float[3];
        float[] encodeLut = buildEncodeLut();

        double halationStrength = 0.0;
        float[] glow = null;
        if (tuned.halation > 0.01) {
            glow = buildGlow(input, width, height, toneLut, adjustLut, lut, decodeLut);
            // 按 profile 的抗光晕层标注调整：无 rem-jet 的 CineStill 光晕最夸张，
            // 抗光晕层强的胶片（如 Portra 系列）收敛更快。
            double filmFactor = 1.0;
            if ("no".equalsIgnoreCase(stock.antihalation)) {
                filmFactor = 1.6;
            } else if ("strong".equalsIgnoreCase(stock.antihalation)) {
                filmFactor = 0.55;
            }
            halationStrength = 0.55 * tuned.halation * filmFactor;
        }

        double grainAmount = 0.10 * tuned.grain;
        int glowWidth = glow == null ? 0 : Math.max(1, width / GLOW_DOWNSCALE);
        int glowHeight = glow == null ? 0 : Math.max(1, height / GLOW_DOWNSCALE);
        float[] weights = MONO_FILTER_WEIGHTS[
                Math.max(0, Math.min(MONO_FILTER_WEIGHTS.length - 1, tuned.monoFilter))];
        // 黑白 LUT 的输出已经是中性灰，颗粒与光晕也应保持中性，避免彩色噪点。
        boolean monoOutput = tuned.monochrome || (lut != null && stock.monochrome);
        float vignette = (float) tuned.vignette;
        float lightLeak = (float) tuned.lightLeak;
        // 强度混合在 sRGB 域完成：0% 就是原图，100% 是完整胶片效果。
        float mixStrength = (float) tuned.strength;
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;
        float[] tile = grainTile();
        // 高光/阴影/白色/黑色是纯亮度调整，参数全为 0 时不建表、也不进入逐像素分支。
        int[] zoneLut = (tuned.highlights != 0.0 || tuned.shadows != 0.0
                || tuned.whites != 0.0 || tuned.blacks != 0.0)
                ? buildZoneLut(tuned) : null;
        // 去雾用 1/8 缩略图的暗通道估计透射率；负值则是往雾灰色收敛。
        float dehaze = (float) tuned.dehaze;
        DehazeMap dehazeMap = null;
        int[] hazeLut = null;
        int[] dehazeColumns = null;
        if (dehaze > 0.01f) {
            dehazeMap = buildDehazeMap(input, dehaze);
            dehazeColumns = new int[width];
            for (int x = 0; x < width; x++) {
                dehazeColumns[x] = Math.min(dehazeMap.width - 1,
                        x * dehazeMap.width / width);
            }
        } else if (dehaze < -0.01f) {
            hazeLut = buildHazeLut(-dehaze);
        }

        // 分条带渲染：只常驻一条带像素，导出 4096px 时不再需要整幅 int[]，内存峰值明显下降。
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int stripRows = Math.min(height, STRIP_ROWS);
        int[] strip = new int[width * stripRows];
        for (int y0 = 0; y0 < height; y0 += stripRows) {
            int rows = Math.min(stripRows, height - y0);
            input.getPixels(strip, 0, width, 0, y0, width, rows);
            for (int ry = 0; ry < rows; ry++) {
                int y = y0 + ry;
                int rowOffset = ry * width;
                float ny = (y - centerY) / centerY;
                int dehazeRow = 0;
                if (dehazeMap != null) {
                    dehazeRow = Math.min(dehazeMap.height - 1,
                            y * dehazeMap.height / height) * dehazeMap.width;
                }
                for (int x = 0; x < width; x++) {
                    int argb = strip[rowOffset + x];
                    int r8 = (argb >>> 16) & 0xff;
                    int g8 = (argb >>> 8) & 0xff;
                    int b8 = argb & 0xff;
                    float red;
                    float green;
                    float blue;
                    if (lut != null) {
                        // LUT 输入是 sRGB 显示值：先折进曝光/白平衡，再查表。
                        lut.sample8(
                                adjustLut[0][r8], adjustLut[1][g8], adjustLut[2][b8], lutOut);
                        // 后续颗粒/光晕/暗角都在线性光里做，和曲线管线保持一致。
                        red = decodeLut[to8(lutOut[0])];
                        green = decodeLut[to8(lutOut[1])];
                        blue = decodeLut[to8(lutOut[2])];
                    } else {
                        red = toneLut[0][r8];
                        green = toneLut[1][g8];
                        blue = toneLut[2][b8];
                    }

                    if (tuned.monochrome) {
                        // 先转黑白：色调映射后的三通道按滤镜权重取灰，去掉胶片的成色偏色。
                        float gray = weights[0] * red + weights[1] * green + weights[2] * blue;
                        red = gray;
                        green = gray;
                        blue = gray;
                    }

                    if (grainAmount > 0.0) {
                        float luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
                        float weight = 4.0f * luminance * (1.0f - luminance);
                        if (weight < 0.0f) weight = 0.0f;
                        float scale = (float) grainAmount * weight;
                        if (monoOutput) {
                            // 黑白颗粒是同一颗银盐，三通道用同一噪声，避免彩色噪点。
                            float factor = 1.0f + grainAt(tile, x, y, 0x51ed) * scale;
                            red *= factor;
                            green = red;
                            blue = red;
                        } else {
                            float common = grainAt(tile, x, y, 0x51ed) * 0.7f;
                            float nr = common + grainAt(tile, x, y, 0x2f1b) * 0.3f;
                            float ng = common + grainAt(tile, x, y, 0x71a3) * 0.3f;
                            float nb = common + grainAt(tile, x, y, 0x19c7) * 0.3f;
                            red *= 1.0f + nr * scale;
                            green *= 1.0f + ng * scale;
                            blue *= 1.0f + nb * scale;
                        }
                    }

                    if (glow != null) {
                        float gx = (x + 0.5f) / GLOW_DOWNSCALE - 0.5f;
                        float gy = (y + 0.5f) / GLOW_DOWNSCALE - 0.5f;
                        float value = sampleGlow(glow, glowWidth, glowHeight, gx, gy);
                        float strength = (float) halationStrength * value;
                        if (monoOutput) {
                            // 黑白的光晕是中性灰溢出，不带暖色。
                            red += strength;
                            green += strength;
                            blue += strength;
                        } else {
                            red += strength;
                            green += strength * 0.40f;
                            blue += strength * 0.16f;
                        }
                    }

                    if (vignette > 0.01) {
                        float nx = (x - centerX) / centerX;
                        float radial = nx * nx + ny * ny;
                        float dim = 1.0f - vignette * 0.9f * Math.min(1.0f, radial * 0.7f);
                        red *= dim;
                        green *= dim;
                        blue *= dim;
                    }

                    if (lightLeak > 0.01) {
                        // 右上角的暖色漏光：向右上角逐渐增强，带一点由暖到橙的过渡。
                        float rx = (width - 1 - x) / (width * 0.45f);
                        if (rx < 0.0f) rx = 0.0f;
                        if (rx > 1.0f) rx = 1.0f;
                        float ty = y / (height * 0.70f);
                        if (ty > 1.0f) ty = 1.0f;
                        float value = (1.0f - rx) * (1.0f - rx) * (1.0f - ty) * (1.0f - ty);
                        float strength = 0.55f * lightLeak * value;
                        red += strength;
                        green += strength * 0.55f;
                        blue += strength * 0.25f;
                    }

                    int r = encode(red, encodeLut);
                    int g = encode(green, encodeLut);
                    int b = encode(blue, encodeLut);
                    if (dehazeMap != null) {
                        float transmission =
                                dehazeMap.transmission[dehazeRow + dehazeColumns[x]];
                        float inverse = 1.0f / transmission;
                        float shift = dehazeMap.light * (inverse - 1.0f);
                        r = clamp8(Math.round(r * inverse - shift));
                        g = clamp8(Math.round(g * inverse - shift));
                        b = clamp8(Math.round(b * inverse - shift));
                    } else if (hazeLut != null) {
                        r = hazeLut[r];
                        g = hazeLut[g];
                        b = hazeLut[b];
                    }
                    if (zoneLut != null) {
                        int luma = (r * 299 + g * 587 + b * 114) / 1000;
                        int delta = zoneLut[luma];
                        r = clamp8(r + delta);
                        g = clamp8(g + delta);
                        b = clamp8(b + delta);
                    }
                    if (mixStrength < 0.999f) {
                        // 与原始 sRGB 像素线性混合；只多三次乘法，不增加额外图像缓冲区。
                        int sr = (argb >>> 16) & 0xff;
                        int sg = (argb >>> 8) & 0xff;
                        int sb = argb & 0xff;
                        r = (int) (r * mixStrength + sr * (1f - mixStrength) + 0.5f);
                        g = (int) (g * mixStrength + sg * (1f - mixStrength) + 0.5f);
                        b = (int) (b * mixStrength + sb * (1f - mixStrength) + 0.5f);
                    }
                    strip[rowOffset + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            output.setPixels(strip, 0, width, 0, y0, width, rows);
        }

        if (tuned.sharpening > 0.01) {
            // 强度映射到 0…0.8，100% 也不会出现生硬的白色描边。
            applySharpen(output, 0.8f * (float) tuned.sharpening);
        }
        if (input != source) input.recycle();
        return output;
    }

    private static void clamp(Settings settings) {
        settings.exposureEv = Math.max(-3.0, Math.min(3.0, settings.exposureEv));
        settings.temperature = Math.max(-1.0, Math.min(1.0, settings.temperature));
        settings.tint = Math.max(-1.0, Math.min(1.0, settings.tint));
        settings.grain = Math.max(0.0, Math.min(1.0, settings.grain));
        settings.halation = Math.max(0.0, Math.min(1.0, settings.halation));
        settings.monoFilter = Math.max(0, Math.min(MONO_FILTER_WEIGHTS.length - 1,
                settings.monoFilter));
        settings.vignette = Math.max(0.0, Math.min(1.0, settings.vignette));
        settings.lightLeak = Math.max(0.0, Math.min(1.0, settings.lightLeak));
        settings.strength = Math.max(0.0, Math.min(1.0, settings.strength));
        settings.highlights = Math.max(-1.0, Math.min(1.0, settings.highlights));
        settings.shadows = Math.max(-1.0, Math.min(1.0, settings.shadows));
        settings.whites = Math.max(-1.0, Math.min(1.0, settings.whites));
        settings.blacks = Math.max(-1.0, Math.min(1.0, settings.blacks));
        settings.sharpening = Math.max(0.0, Math.min(1.0, settings.sharpening));
        settings.dehaze = Math.max(-1.0, Math.min(1.0, settings.dehaze));
    }

    /**
     * 高光/阴影/白色/黑色调整表：按输出亮度给一个等量的三通道偏移，因此只改变明暗、不改变色相。
     * 各段用平滑权重过渡，避免在中间调出现可见的分界。
     */
    private static int[] buildZoneLut(Settings settings) {
        int[] lut = new int[256];
        float highlights = (float) settings.highlights;
        float shadows = (float) settings.shadows;
        float whites = (float) settings.whites;
        float blacks = (float) settings.blacks;
        for (int level = 0; level < 256; level++) {
            float luma = level / 255.0f;
            float highlightWeight = smoothstep(0.45f, 1.0f, luma);
            float shadowWeight = 1.0f - smoothstep(0.0f, 0.55f, luma);
            float whiteWeight = smoothstep(0.62f, 1.0f, luma);
            float blackWeight = 1.0f - smoothstep(0.0f, 0.38f, luma);
            float target = luma + highlights * highlightWeight * 0.35f
                    + shadows * shadowWeight * 0.35f
                    + whites * whiteWeight * 0.28f
                    + blacks * blackWeight * 0.28f;
            target = Math.max(0.0f, Math.min(1.0f, target));
            lut[level] = Math.round((target - luma) * 255.0f);
        }
        return lut;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static int clamp8(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /** 去雾用的低分辨率传输率图：按暗通道估计每个区域的雾浓度。 */
    private static final class DehazeMap {
        final float light;
        final int width;
        final int height;
        final float[] transmission;

        DehazeMap(float light, int width, int height, float[] transmission) {
            this.light = light;
            this.width = width;
            this.height = height;
            this.transmission = transmission;
        }
    }

    /** 缩到 1/8 估算暗通道与大气光，再得到每块的透射率；逐像素只做一次查表与缩放。 */
    private static DehazeMap buildDehazeMap(Bitmap input, float strength) {
        int width = Math.max(1, input.getWidth() / 8);
        int height = Math.max(1, input.getHeight() / 8);
        Bitmap small = Bitmap.createScaledBitmap(input, width, height, true);
        int[] pixels = new int[width * height];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        small.recycle();
        int[] dark = new int[pixels.length];
        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int value = Math.min((pixel >>> 16) & 0xff,
                    Math.min((pixel >>> 8) & 0xff, pixel & 0xff));
            dark[i] = value;
            histogram[value]++;
        }
        // 大气光取暗通道的高分位：有雾时这个值被抬亮，无雾时接近黑位。
        int wanted = Math.max(1, (int) (pixels.length * 0.99f));
        int light = 255;
        int count = 0;
        for (int level = 255; level >= 0; level--) {
            count += histogram[level];
            if (count >= wanted) {
                light = level;
                break;
            }
        }
        light = Math.max(40, light);
        float omega = 0.9f * strength;
        float[] transmission = new float[dark.length];
        for (int i = 0; i < transmission.length; i++) {
            float t = 1.0f - omega * (dark[i] / (float) light);
            transmission[i] = Math.max(0.35f, Math.min(1.0f, t));
        }
        return new DehazeMap(light, width, height, transmission);
    }

    /** 反向参数（加雾）：把颜色往雾灰色收敛，用于对照或营造空气感。 */
    private static int[] buildHazeLut(float strength) {
        int[] lut = new int[256];
        float haze = 0.45f * strength;
        float light = 200.0f;
        for (int level = 0; level < 256; level++) {
            lut[level] = clamp8(Math.round(level * (1.0f - haze) + light * haze));
        }
        return lut;
    }

    /**
     * USM 锐化：用 1/3 缩略图的两次盒式模糊得到低频，再按强度加回高频。
     * 只在锐化参数大于 0 时执行，多一次整图读取，不影响默认渲染路径。
     */
    private static void applySharpen(Bitmap image, float amount) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 3 || height < 3) return;
        int lowWidth = Math.max(1, width / 3);
        int lowHeight = Math.max(1, height / 3);
        Bitmap low = Bitmap.createScaledBitmap(image, lowWidth, lowHeight, true);
        int[] blurred = new int[lowWidth * lowHeight];
        low.getPixels(blurred, 0, lowWidth, 0, 0, lowWidth, lowHeight);
        low.recycle();
        blurred = boxBlur(blurred, lowWidth, lowHeight);
        blurred = boxBlur(blurred, lowWidth, lowHeight);

        int[] columnIndex = new int[width];
        for (int x = 0; x < width; x++) {
            columnIndex[x] = Math.min(lowWidth - 1, x * lowWidth / width);
        }
        int stripRows = Math.min(height, STRIP_ROWS);
        int[] strip = new int[width * stripRows];
        for (int y0 = 0; y0 < height; y0 += stripRows) {
            int rows = Math.min(stripRows, height - y0);
            image.getPixels(strip, 0, width, 0, y0, width, rows);
            for (int ry = 0; ry < rows; ry++) {
                int y = y0 + ry;
                int lowRow = Math.min(lowHeight - 1, y * lowHeight / height) * lowWidth;
                int rowOffset = ry * width;
                for (int x = 0; x < width; x++) {
                    int base = blurred[lowRow + columnIndex[x]];
                    int argb = strip[rowOffset + x];
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    int br = (base >>> 16) & 0xff;
                    int bg = (base >>> 8) & 0xff;
                    int bb = base & 0xff;
                    r = clamp8((int) (r + (r - br) * amount));
                    g = clamp8((int) (g + (g - bg) * amount));
                    b = clamp8((int) (b + (b - bb) * amount));
                    strip[rowOffset + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            image.setPixels(strip, 0, width, 0, y0, width, rows);
        }
    }

    private static int[] boxBlur(int[] source, int width, int height) {
        int[] output = new int[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= height) continue;
                    int row = yy * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= width) continue;
                        int pixel = source[row + xx];
                        red += (pixel >>> 16) & 0xff;
                        green += (pixel >>> 8) & 0xff;
                        blue += pixel & 0xff;
                        count++;
                    }
                }
                output[y * width + x] = 0xff000000
                        | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
            }
        }
        return output;
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
        boolean positive = stock.isPositive();
        boolean monochrome = stock.monochrome;
        double[] printMidLog = new double[3];
        double[] printGain = new double[3];
        double[] filmMidDensity = new double[3];
        /** 18% 场景中灰落在 logE 轴上的位置；负片取轴中点，正片按密度反查。 */
        double[] filmAnchorLog = new double[3];
        int last = FilmStock.CURVE_POINTS - 1;
        for (int channel = 0; channel < 3; channel++) {
            if (positive) {
                // 反转片：D 随曝光下降，18% 灰对应密度 0.745（透过率 18%）。
                filmAnchorLog[channel] = invertFilmLog(stock, channel, midPrintDensity);
                filmMidDensity[channel] = midPrintDensity;
                continue;
            }
            filmMidDensity[channel] = sampleFilmDensity(stock, channel, filmMidLog);
            filmAnchorLog[channel] = filmMidLog;
            if (monochrome) continue; // 黑白走下面的中性相纸近似，不用彩色相纸曲线。
            printMidLog[channel] = invertPrintLog(stock, channel, midPrintDensity);
            double printMax = stock.printDensity(channel, last);
            double whiteLog = invertPrintLog(stock, channel, 0.05);
            double blackLog = invertPrintLog(stock, channel,
                    Math.max(whiteLog + 0.05, printMax - 0.05));
            double filmSpan = Math.max(0.1, stock.density(channel, last) - stock.density(channel, 0));
            // 曲线若有轻微非单调/肩部截断，黑白跨度可能极小；给一个下限，避免反差系数变成负数。
            printGain[channel] = Math.max(0.05, (blackLog - whiteLog) / filmSpan);
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
                double logExposure = Math.log10(Math.max(linear, 1e-6) / MID_GRAY)
                        + filmAnchorLog[channel];
                double filmDensity = sampleFilmDensity(stock, channel, logExposure);
                double mapped;
                if (positive) {
                    // 幻灯片直接看：密度差 → 透过率，按 18% 灰归一，超出 1 的部分裁成白。
                    mapped = MID_GRAY * Math.pow(10.0, filmMidDensity[channel] - filmDensity);
                } else if (monochrome) {
                    // 彩色相纸的逐通道密度不能直接当 RGB 用（会让黑白图偏色），
                    // 这里用中性灰相纸近似：18% 锚点 + 固定反差，并限制在黑/白密度内。
                    double paperDensity = midPrintDensity
                            + (filmMidDensity[channel] - filmDensity) * MONO_PRINT_GAMMA;
                    paperDensity = Math.max(MONO_PRINT_MIN_DENSITY,
                            Math.min(MONO_PRINT_MAX_DENSITY, paperDensity));
                    mapped = Math.pow(10.0, -paperDensity);
                } else {
                    double printLog = printMidLog[channel]
                            + (filmMidDensity[channel] - filmDensity) * printGain[channel];
                    mapped = Math.pow(10.0, -samplePrintDensity(stock, channel, printLog));
                }
                lut[channel][value] = (float) Math.min(1.0, Math.max(0.0, mapped));
            }
        }
        return lut;
    }

    /** 反查反转片的 logE：密度达到 targetDensity 处的曝光（D 随曝光下降，二分）。 */
    private static double invertFilmLog(FilmStock stock, int channel, double targetDensity) {
        double low = stock.logExposureMin;
        double high = stock.logExposureMax;
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) * 0.5;
            if (sampleFilmDensity(stock, channel, mid) > targetDensity) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
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

    /** sRGB 显示值 → 线性光：LUT 输出要回到线性域，才能沿用同一套颗粒/光晕。 */
    private static float[] buildDecodeLut() {
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = (float) srgbToLinear(i / 255.0);
        }
        return lut;
    }

    /**
     * LUT 管线的前置曝光/白平衡：在 sRGB 域先把三个通道折进 8bit，
     * 再交给 3D LUT 查表；曲线管线仍走自己的一体化 toneLut。
     */
    private static int[][] buildAdjustLut(Settings settings) {
        double temperature = 0.28 * settings.temperature;
        double tint = settings.tint;
        double[] gain = new double[3];
        gain[0] = (1.0 + temperature) * (1.0 + 0.12 * tint);
        gain[1] = 1.0 - 0.20 * tint;
        gain[2] = (1.0 - temperature) * (1.0 + 0.12 * tint);
        double exposureGain = Math.pow(2.0, settings.exposureEv);

        int[][] lut = new int[3][256];
        for (int channel = 0; channel < 3; channel++) {
            for (int value = 0; value < 256; value++) {
                double linear = srgbToLinear(value / 255.0) * gain[channel] * exposureGain;
                lut[channel][value] = to8((float) linearToSrgb(linear));
            }
        }
        return lut;
    }

    private static int to8(float value) {
        if (value <= 0.0f) return 0;
        if (value >= 1.0f) return 255;
        return (int) (value * 255.0f + 0.5f);
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
    private static float[] buildGlow(
            Bitmap input,
            int width,
            int height,
            float[][] toneLut,
            int[][] adjustLut,
            Lut3D lut,
            float[] decodeLut) {
        int glowWidth = Math.max(1, width / GLOW_DOWNSCALE);
        int glowHeight = Math.max(1, height / GLOW_DOWNSCALE);
        int[] smallPixels;
        if (glowWidth == width && glowHeight == height) {
            smallPixels = new int[width * height];
            input.getPixels(smallPixels, 0, width, 0, 0, width, height);
        } else {
            // 先缩到 1/4 边长再算掩码：既降低模糊成本，也避免为全图再开一份 int[]。
            Bitmap small = Bitmap.createScaledBitmap(input, glowWidth, glowHeight, true);
            smallPixels = new int[glowWidth * glowHeight];
            small.getPixels(smallPixels, 0, glowWidth, 0, 0, glowWidth, glowHeight);
            if (small != input) small.recycle();
        }
        float[] mask = new float[glowWidth * glowHeight];
        float[] lutOut = lut == null ? null : new float[3];
        for (int i = 0; i < mask.length; i++) {
            int argb = smallPixels[i];
            float lred;
            float lgreen;
            float lblue;
            if (lut != null) {
                lut.sample8(
                        adjustLut[0][(argb >>> 16) & 0xff],
                        adjustLut[1][(argb >>> 8) & 0xff],
                        adjustLut[2][argb & 0xff],
                        lutOut);
                lred = decodeLut[to8(lutOut[0])];
                lgreen = decodeLut[to8(lutOut[1])];
                lblue = decodeLut[to8(lutOut[2])];
            } else {
                lred = toneLut[0][(argb >>> 16) & 0xff];
                lgreen = toneLut[1][(argb >>> 8) & 0xff];
                lblue = toneLut[2][argb & 0xff];
            }
            float luminance = 0.2126f * lred + 0.7152f * lgreen + 0.0722f * lblue;
            float value = (luminance - 0.6f) / 0.4f;
            if (value <= 0.0f) continue;
            mask[i] = value * value;
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

    /**
     * 颗粒贴图：两级噪声叠加成 512×512 的可平铺纹理。
     *
     * <p>粗层用 256×256 随机格点做双线性插值（周期正好 512 像素），得到 2 像素左右的
     * 簇状结构；细层再用逐像素哈希补上高频。相比纯白噪声，颗粒更像银盐的团簇。
     */
    private static float[] grainTile() {
        float[] tile = grainTile;
        if (tile != null) return tile;
        synchronized (FilmSimulator.class) {
            if (grainTile != null) return grainTile;
            int latticeSize = GRAIN_TILE_SIZE / 2;
            float[] lattice = new float[latticeSize * latticeSize];
            for (int y = 0; y < latticeSize; y++) {
                for (int x = 0; x < latticeSize; x++) {
                    lattice[y * latticeSize + x] = hashNoise(x, y, 0x9e37);
                }
            }
            float[] built = new float[GRAIN_TILE_SIZE * GRAIN_TILE_SIZE];
            float max = 0.0001f;
            for (int y = 0; y < GRAIN_TILE_SIZE; y++) {
                for (int x = 0; x < GRAIN_TILE_SIZE; x++) {
                    float coarse = valueNoise(x * 0.5f, y * 0.5f, lattice, latticeSize);
                    float fine = hashNoise(x, y, 0x2f1b);
                    float value = coarse * 0.65f + fine * 0.35f;
                    built[y * GRAIN_TILE_SIZE + x] = value;
                    float magnitude = Math.abs(value);
                    if (magnitude > max) max = magnitude;
                }
            }
            // 归一化到约 [-1,1]，保证滑杆 100% 时的颗粒幅度与旧实现接近。
            float scale = 1.05f / max;
            for (int i = 0; i < built.length; i++) {
                float value = built[i] * scale;
                built[i] = value < -1.0f ? -1.0f : value > 1.0f ? 1.0f : value;
            }
            grainTile = built;
            return built;
        }
    }

    private static float grainAt(float[] tile, int x, int y, int seed) {
        int ix = (x + (seed & 0x3f)) & GRAIN_TILE_MASK;
        int iy = (y + ((seed >>> 6) & 0x3f)) & GRAIN_TILE_MASK;
        return tile[iy * GRAIN_TILE_SIZE + ix];
    }

    private static float valueNoise(float x, float y, float[] lattice, int size) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        fx = fx * fx * (3.0f - 2.0f * fx);
        fy = fy * fy * (3.0f - 2.0f * fy);
        int xa = x0 % size;
        int ya = y0 % size;
        int xb = (xa + 1) % size;
        int yb = (ya + 1) % size;
        float top = lattice[ya * size + xa] * (1.0f - fx) + lattice[ya * size + xb] * fx;
        float bottom = lattice[yb * size + xa] * (1.0f - fx) + lattice[yb * size + xb] * fx;
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
