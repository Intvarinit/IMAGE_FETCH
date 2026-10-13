package com.imagefetch.ptp;

import java.util.Arrays;

/**
 * 对焦方式（{@code 0xD1BA EOS_LvAfSystem}）下拉框的选项列表构造规则。
 *
 * <p>相机不一定上报允许值列表（{@code 0xC18A}）：上报为空时旧实现退化成「只有当前值一项」，
 * 下拉框里根本切不动——这正是「对焦点切换没效果」的一个可解释来源。这里给出降级规则：
 * 上报为空就用候选全表（{@code 0..14}，对照 libgphoto2 {@code config.c} 的
 * {@code canon_eos_afmethod}），并在界面标注「未获相机确认」；上报的列表不含当前值时，
 * 把当前值追加到末尾并选中它，避免下游把第 0 项错显为选中。
 *
 * <p>纯算术，不碰 Android，可直接单测。
 */
public final class AfMethodChoices {
    /** 相机没给允许列表时的候选编码，对照 libgphoto2 {@code config.c} 的 {@code canon_eos_afmethod}。 */
    private static final int[] CANDIDATE_CODES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
    };

    private AfMethodChoices() {
    }

    /** 解析结果：{@code values} 为下拉框选项编码，{@code currentIndex} 为当前值下标。 */
    public static final class Choices {
        public final int[] values;
        public final int currentIndex;
        /** true 表示 {@code values} 来自相机上报；false 表示是用候选全表降级的。 */
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
     * @param currentRaw 相机当前的对焦方式原始值
     */
    public static Choices resolve(int[] reported, int currentRaw) {
        if (reported != null && reported.length > 0) {
            for (int i = 0; i < reported.length; i++) {
                if (reported[i] == currentRaw) {
                    return new Choices(reported.clone(), i, true);
                }
            }
            // 上报的列表不含当前值：追加并选中它，否则下游会把第 0 项错显为选中（显示值 ≠ 相机值）。
            int[] merged = Arrays.copyOf(reported, reported.length + 1);
            merged[reported.length] = currentRaw;
            return new Choices(merged, merged.length - 1, true);
        }
        // 相机没给允许列表：退化为候选全表。选到相机不支持的编码时 0x9110 会回 0x2019，
        // 写入路径会如实报错——这就是候选的诚实边界。
        int[] candidates = CANDIDATE_CODES.clone();
        int index = indexOf(candidates, currentRaw);
        if (index < 0) {
            candidates = Arrays.copyOf(candidates, candidates.length + 1);
            candidates[candidates.length - 1] = currentRaw;
            index = candidates.length - 1;
        }
        return new Choices(candidates, index, false);
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return -1;
    }
}
