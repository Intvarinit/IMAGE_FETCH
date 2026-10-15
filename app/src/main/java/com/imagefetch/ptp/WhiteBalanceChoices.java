package com.imagefetch.ptp;

/**
 * 白平衡（Canon EOS {@code 0xD109 EOS_WhiteBalance}，u8 枚举）下拉框的选项列表与名称。
 *
 * <p>取值表取自 libgphoto2 {@code config.c:6843-6863} 的 {@code canon_eos_whitebalance}，
 * 中文名沿用佳能机身菜单的用词；表里有空洞（13/14/17/22 没有条目），这些码一律不列进候选，
 * 只在相机真的报了它们时按「未知编码」显示。
 *
 * <p>相机不一定上报允许值列表（{@code 0xC18A}）：上报为空时退化成候选表，并标注「未获相机确认」；
 * 上报的列表不含当前值时把当前值追加到末尾并选中，避免下游把第 0 项错显为选中。
 * 与 {@link AfMethodChoices} 同一套规则，只是这里传的是编码而不是下标。
 *
 * <p>纯算术，不碰 Android，可直接单测。
 */
public final class WhiteBalanceChoices {
    /** 「色温」档的编码：选中它才该露出单独填 K 值的输入行。 */
    public static final int COLOR_TEMPERATURE = 9;

    /** 相机没给允许列表时的候选编码（libgphoto2 {@code canon_eos_whitebalance} 的已知码）。 */
    private static final int[] CANDIDATE_CODES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 18, 19, 20, 21, 23,
    };

    private WhiteBalanceChoices() {
    }

    /** 解析结果：{@code values} 为下拉框选项编码，{@code currentIndex} 为当前值下标。 */
    public static final class Choices {
        public final int[] values;
        public final int currentIndex;
        /** true 表示 {@code values} 来自相机上报；false 表示是用候选表降级的。 */
        public final boolean fromCamera;

        Choices(int[] values, int currentIndex, boolean fromCamera) {
            this.values = values;
            this.currentIndex = currentIndex;
            this.fromCamera = fromCamera;
        }
    }

    /**
     * 由相机上报的允许列表与当前值构造下拉框选项。
     *
     * @param reported   相机上报的允许值（可能为 null 或空）
     * @param currentRaw 相机当前的白平衡编码值
     */
    public static Choices resolve(int[] reported, int currentRaw) {
        if (reported != null && reported.length > 0) {
            for (int i = 0; i < reported.length; i++) {
                if (reported[i] == currentRaw) {
                    return new Choices(reported.clone(), i, true);
                }
            }
            // 上报的列表不含当前值：追加并选中它，否则下游会把第 0 项错显为选中（显示值 ≠ 相机值）。
            int[] merged = new int[reported.length + 1];
            System.arraycopy(reported, 0, merged, 0, reported.length);
            merged[reported.length] = currentRaw;
            return new Choices(merged, merged.length - 1, true);
        }
        // 相机没给允许列表：退化为候选表。选到相机不支持的编码时 0x9110 会回 0x2019，
        // 写入路径会如实报错——这就是候选的诚实边界。
        int[] candidates = CANDIDATE_CODES.clone();
        int index = indexOf(candidates, currentRaw);
        if (index < 0) {
            candidates = append(candidates, currentRaw);
            index = candidates.length - 1;
        }
        return new Choices(candidates, index, false);
    }

    /** 编码 → 中文名；未登记（含表里的空洞）时返回「未知编码 N」，绝不返回 null。 */
    public static String label(int code) {
        switch (code) {
            case 0:
                return "自动";
            case 1:
                return "日光";
            case 2:
                return "阴天";
            case 3:
                return "白炽灯";
            case 4:
                return "荧光灯";
            case 5:
                return "闪光灯";
            case 6:
                return "自定义";
            case 7:
                return "未知 7";
            case 8:
                return "阴影";
            case 9:
                return "色温";
            case 10:
                return "自定义 PC-1";
            case 11:
                return "自定义 PC-2";
            case 12:
                return "自定义 PC-3";
            case 15:
                return "手动 2";
            case 16:
                return "手动 3";
            case 18:
                return "手动 4";
            case 19:
                return "手动 5";
            case 20:
                return "自定义 PC-4";
            case 21:
                return "自定义 PC-5";
            case 23:
                return "AWB 白色优先";
            default:
                return "未知编码 " + code;
        }
    }

    private static int[] append(int[] values, int value) {
        int[] grown = new int[values.length + 1];
        System.arraycopy(values, 0, grown, 0, values.length);
        grown[values.length] = value;
        return grown;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return -1;
    }
}
