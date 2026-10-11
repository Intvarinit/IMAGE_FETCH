package com.imagefetch.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 对焦包围参数计算器：由「焦段 + 光圈 + 拍摄距离范围 + 重叠率」算出机内对焦包围该填的张数、
 * 对焦增量与曝光平滑化，供用户一键填进 {@code 0xD208} 设置面板。
 *
 * <h3>模型与证据等级</h3>
 * 纯光学部分（超焦距、景深限）是教科书公式，可靠；但<b>「对焦增量」与物理步长的换算没有任何
 * 实测依据</b>——佳能的增量档位既没文档，也没在本机上标定过。所以：
 * <ul>
 *   <li>{@link #INCREMENT_MM_PER_STEP} 是从一个已知算例（50mm f/8、0.5–1.5m、重叠 40% →
 *       步长 26mm、增量 5）反推的<b>经验常数</b>，报告里一律标注「未在机身实测证实」。</li>
 *   <li>{@link #DEPTH_RATIO_COEFFICIENT} 的「按景深比例」是另一种常见猜测。它<b>数学上是退化的</b>：
 *       换算结果 = {@code (1 − 重叠率) / 系数}，与焦段、光圈、距离全无关，重叠率 40% 时恒为 5。
 *       所以它只作为对照展示，价值仅在于「两个模型一致时可参考、不一致说明经验成分大」。</li>
 * </ul>
 *
 * <p>这是纯计算，不碰相机的任何命令；写机由既有的 {@code FocusBracketPlan} 负责。
 */
public final class FocusBracketCalculator {
    /** 全画幅弥散圆直径（mm）。EOS RP 是全画幅，取业界常用的 0.03。 */
    public static final double SENSOR_COC_MM = 0.03;
    /**
     * 对焦增量每档对应的物理步长（mm）。<b>经验标定常数，未在机身实测证实</b>——
     * 由「步长 25.9mm ↔ 增量 5」反推得到，不同镜头与机身可能有差异。
     */
    public static final double INCREMENT_MM_PER_STEP = 5.2;
    /** 默认重叠率（%）。 */
    public static final int DEFAULT_OVERLAP_PERCENT = 40;
    /** 「按景深比例」对照模型的系数：假设步长 = 增量 × 景深 × 该系数。 */
    public static final double DEPTH_RATIO_COEFFICIENT = 0.12;

    /** 允许的焦段范围（mm）。 */
    public static final double MIN_FOCAL_MM = 1.0;
    public static final double MAX_FOCAL_MM = 2000.0;
    /** 允许的光圈范围。 */
    public static final double MIN_APERTURE = 0.7;
    public static final double MAX_APERTURE = 64.0;
    /** 允许的重叠率范围（%）。 */
    public static final int MIN_OVERLAP_PERCENT = 0;
    public static final int MAX_OVERLAP_PERCENT = 95;

    /** 场景预设：只提供一组「距离范围 + 重叠率」的默认值，界面上可改。 */
    public enum Scene {
        /** 微距：花卉、昆虫。 */
        MACRO("微距（花卉、昆虫）", 200, 400),
        /** 产品：小物件。 */
        PRODUCT("产品（小物件）", 500, 1500),
        /** 风光：带前景的广角。 */
        LANDSCAPE("风光（带前景）", 1000, 8000),
        /** 建筑：大场景。 */
        ARCHITECTURE("建筑（大场景）", 2000, 20000);

        private final String label;
        private final double defaultNearMm;
        private final double defaultFarMm;

        Scene(String label, double defaultNearMm, double defaultFarMm) {
            this.label = label;
            this.defaultNearMm = defaultNearMm;
            this.defaultFarMm = defaultFarMm;
        }

        public String label() {
            return label;
        }

        public double defaultNearMm() {
            return defaultNearMm;
        }

        public double defaultFarMm() {
            return defaultFarMm;
        }

        public int defaultOverlapPercent() {
            return DEFAULT_OVERLAP_PERCENT;
        }
    }

    /** 计算结果：中间量全部保留，报告里逐项摊开，方便用户自己核对而不是只给个结论。 */
    public static final class Result {
        public final double focalMm;
        public final double aperture;
        public final double nearMm;
        public final double farMm;
        public final int overlapPercent;
        public final double hyperfocalMm;
        public final double nearLimitMm;
        /** 远界；{@link #dofInfinite} 为真时无意义。 */
        public final double farLimitMm;
        /** 单张在近端的景深；{@link #dofInfinite} 为真时无意义。 */
        public final double dofMm;
        /** 相邻两张的焦点移动步长；{@link #dofInfinite} 为真时无意义。 */
        public final double stepMm;
        /** 近端已经超过超焦距：单张就覆盖到无穷远，不需要包围。 */
        public final boolean dofInfinite;
        public final int count;
        /** 主推增量：按固定毫米常数 {@link #INCREMENT_MM_PER_STEP} 换算。 */
        public final int incrementPrimary;
        /** 对照增量：按「步长 = 增量 × 景深 × 系数」换算。 */
        public final int incrementDepthBased;
        /** 曝光平滑化取值（0/1）。 */
        public final int smoothing;
        private final List<String> notes;

        Result(double focalMm, double aperture, double nearMm, double farMm, int overlapPercent,
               double hyperfocalMm, double nearLimitMm, double farLimitMm, double dofMm,
               double stepMm, boolean dofInfinite, int count, int incrementPrimary,
               int incrementDepthBased, int smoothing, List<String> notes) {
            this.focalMm = focalMm;
            this.aperture = aperture;
            this.nearMm = nearMm;
            this.farMm = farMm;
            this.overlapPercent = overlapPercent;
            this.hyperfocalMm = hyperfocalMm;
            this.nearLimitMm = nearLimitMm;
            this.farLimitMm = farLimitMm;
            this.dofMm = dofMm;
            this.stepMm = stepMm;
            this.dofInfinite = dofInfinite;
            this.count = count;
            this.incrementPrimary = incrementPrimary;
            this.incrementDepthBased = incrementDepthBased;
            this.smoothing = smoothing;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }

        public List<String> notes() {
            return notes;
        }

        /** 给界面看的多行报告：每个中间量都摊开，并带上证据等级标注。 */
        public String report() {
            StringBuilder text = new StringBuilder();
            text.append(String.format(Locale.ROOT,
                    "输入：焦段 %.0f mm · 光圈 f/%s · 距离 %.0f–%.0f mm · 重叠率 %d%%%n",
                    focalMm, trim(aperture), nearMm, farMm, overlapPercent));
            text.append(String.format(Locale.ROOT, "超焦距 %.0f mm%n", hyperfocalMm));

            if (dofInfinite) {
                text.append(String.format(Locale.ROOT,
                        "近端 %.0f mm 已达到/超过超焦距：单张就清晰到无穷远，不需要对焦包围。%n",
                        nearMm));
            } else {
                text.append(String.format(Locale.ROOT,
                        "近端景深限 %.1f–%.1f mm（单张景深 %.1f mm）%n",
                        nearLimitMm, farLimitMm, dofMm));
                text.append(String.format(Locale.ROOT,
                        "步长（保相邻两张重叠 %d%%）%.1f mm%n", overlapPercent, stepMm));
            }

            text.append(String.format(Locale.ROOT, "%n推荐张数：%d%n", count));
            text.append(String.format(Locale.ROOT,
                    "对焦增量：%d（按 %.1f mm/档 换算）%n", incrementPrimary, INCREMENT_MM_PER_STEP));
            text.append(String.format(Locale.ROOT,
                    "  对照：按景深比例模型 → %d%n", incrementDepthBased));
            text.append(String.format(Locale.ROOT,
                    "曝光平滑化：%s%n", smoothing == FocusBracketPlan.SMOOTHING_ON ? "开启" : "关闭"));

            for (String note : notes) {
                text.append("\n· ").append(note);
            }
            return text.toString();
        }

        /** 一行摘要，用于状态栏与「填入」后的提示。 */
        public String summary() {
            return "张数 " + count + " / 增量 " + incrementPrimary + " / 平滑化 "
                    + (smoothing == FocusBracketPlan.SMOOTHING_ON ? "开启" : "关闭");
        }
    }

    private FocusBracketCalculator() {
    }

    /** 按场景预设的默认距离与重叠率计算。 */
    public static Result compute(Scene scene, double focalMm, double aperture) {
        return compute(focalMm, aperture, scene.defaultNearMm(), scene.defaultFarMm(),
                scene.defaultOverlapPercent());
    }

    /**
     * 核心计算。
     *
     * @param focalMm        焦段（mm）
     * @param aperture       光圈 F 值
     * @param nearMm         拍摄范围最近端（mm）
     * @param farMm          拍摄范围最远端（mm）
     * @param overlapPercent 期望的相邻两张重叠率（%）
     */
    public static Result compute(double focalMm, double aperture, double nearMm, double farMm,
                                 int overlapPercent) {
        requireRange(focalMm, MIN_FOCAL_MM, MAX_FOCAL_MM, "焦段（mm）");
        requireRange(aperture, MIN_APERTURE, MAX_APERTURE, "光圈");
        requireRange(nearMm, 1.0, 1_000_000.0, "最近距离（mm）");
        requireRange(farMm, 1.0, 1_000_000.0, "最远距离（mm）");
        if (nearMm >= farMm) {
            throw new IllegalArgumentException(
                    "最近距离必须小于最远距离（当前 " + trim(nearMm) + " / " + trim(farMm) + " mm）");
        }
        requireRange(overlapPercent, MIN_OVERLAP_PERCENT, MAX_OVERLAP_PERCENT, "重叠率（%）");

        List<String> notes = new ArrayList<>();

        // 光学部分：超焦距与近端景深限。都按全画幅弥散圆算，单位统一为 mm。
        double hyperfocal = focalMm * focalMm / (aperture * SENSOR_COC_MM) + focalMm;
        // 近端一旦达到超焦距，远界就是无穷远——这时公式里的远界分母会变成零或负数，
        // 必须单独走一条分支，而不是硬算出一个假数。
        boolean dofInfinite = nearMm >= hyperfocal;
        double nearLimit = 0;
        double farLimit = 0;
        double dof = 0;
        double step = 0;
        int count;
        int incrementPrimary;
        int incrementDepthBased;

        if (dofInfinite) {
            // 单张覆盖到无穷远，最少张数即可；增量没有意义，按上限给出并说明。
            count = (int) FocusBracketPlan.MIN_COUNT;
            incrementPrimary = (int) FocusBracketPlan.MAX_INCREMENT;
            incrementDepthBased = (int) FocusBracketPlan.MAX_INCREMENT;
            notes.add("近端已在超焦距之外，单张即可覆盖到无穷远；张数按机身下限 "
                    + FocusBracketPlan.MIN_COUNT + " 张给出。");
        } else {
            nearLimit = farLimitOfNearEnd(hyperfocal, focalMm, nearMm, true);
            farLimit = farLimitOfNearEnd(hyperfocal, focalMm, nearMm, false);
            dof = farLimit - nearLimit;
            step = dof * (1.0 - overlapPercent / 100.0);

            long rawCount = Math.round((farMm - nearMm) / step) + 1;
            long clampedCount = clamp(rawCount, FocusBracketPlan.MIN_COUNT,
                    FocusBracketPlan.MAX_COUNT);
            if (clampedCount != rawCount) {
                notes.add("按公式需要 " + rawCount + " 张，已夹到机身允许的 "
                        + FocusBracketPlan.MIN_COUNT + "–" + FocusBracketPlan.MAX_COUNT + " 张。");
            }
            count = (int) clampedCount;

            incrementPrimary = clampIncrement(Math.round(step / INCREMENT_MM_PER_STEP));
            incrementDepthBased = clampIncrement(
                    Math.round(step / (dof * DEPTH_RATIO_COEFFICIENT)));
            if (step / INCREMENT_MM_PER_STEP > FocusBracketPlan.MAX_INCREMENT) {
                notes.add("所需步长换算成增量已超过机身上限 " + FocusBracketPlan.MAX_INCREMENT
                        + "；请缩小拍摄距离范围，或收小光圈减小步长需求。");
            } else if (step / INCREMENT_MM_PER_STEP < FocusBracketPlan.MIN_INCREMENT) {
                notes.add("所需步长换算成增量已到机身下限 " + FocusBracketPlan.MIN_INCREMENT
                        + "，焦点会走得比需要的更密（张数偏多但不会漏焦）。");
            }
        }

        notes.add("「对焦增量」的换算来自经验标定常数 " + trim(INCREMENT_MM_PER_STEP)
                + " mm/档，未在机身上实测证实；不同镜头与机身可能有差异，建议先试拍一组核对。");
        notes.add("对照的「景深比例」模型等于 (1 − 重叠率) / " + trim(DEPTH_RATIO_COEFFICIENT)
                + "，与焦段、光圈、距离都无关——重叠率 " + DEFAULT_OVERLAP_PERCENT
                + "% 时恒为 5。它与主推值不一致时，说明经验成分较大。");
        notes.add("试拍后如有模糊断层：减小增量或增大张数；想精简张数：缩小拍摄距离范围或收小光圈。");

        return new Result(focalMm, aperture, nearMm, farMm, overlapPercent, hyperfocal,
                nearLimit, farLimit, dof, step, dofInfinite, count, incrementPrimary,
                incrementDepthBased, (int) FocusBracketPlan.SMOOTHING_ON, notes);
    }

    /**
     * 由近端的景深限反解。{@code nearEnd} 为真取近界，为假取远界：
     * {@code near = H·u / (H + (u − f))}、{@code far = H·u / (H − (u − f))}。
     * 调用前必须已确认 {@code u < 超焦距}，否则远界分母会变成零或负数。
     */
    private static double farLimitOfNearEnd(double hyperfocal, double focalMm, double distanceMm,
                                            boolean nearEnd) {
        double denominator = nearEnd
                ? hyperfocal + (distanceMm - focalMm)
                : hyperfocal - (distanceMm - focalMm);
        if (denominator <= 0) {
            throw new IllegalArgumentException("该距离已达到超焦距，远界为无穷远，无法求有限景深");
        }
        return hyperfocal * distanceMm / denominator;
    }

    private static int clampIncrement(long value) {
        return (int) clamp(value, FocusBracketPlan.MIN_INCREMENT, FocusBracketPlan.MAX_INCREMENT);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void requireRange(double value, double min, double max, String label) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    label + "必须落在 " + trim(min) + "–" + trim(max) + " 之间（当前 " + trim(value) + "）");
        }
    }

    /** 去掉整数值末尾的 {@code .0}，让报告里的 8 不会写成 8.0。 */
    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}