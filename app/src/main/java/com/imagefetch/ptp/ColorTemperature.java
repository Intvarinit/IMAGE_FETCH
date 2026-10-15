package com.imagefetch.ptp;

/**
 * 白平衡「色温」档的 K 值输入规则（Canon EOS {@code 0xD10A EOS_ColorTemperature}）。
 *
 * <p>这个属性是普通 u32 标量、<b>没有允许值列表</b>，所以选择范围只能由本类给出。
 * 区间 {@code 2500–10000 K}、步进 {@code 100 K} 取自佳能机身菜单里色温滑条的常见范围与刻度，
 * <b>未在相机端实测证实</b>——界面会照实标注，相机若拒绝会由写入路径与用户老实说。
 *
 * <p>纯算术，不碰 Android，可直接单测。
 */
public final class ColorTemperature {
    /** 允许的最低色温（K）。 */
    public static final int MIN_K = 2500;
    /** 允许的最高色温（K）。 */
    public static final int MAX_K = 10000;
    /** 机身滑条的最小刻度（K）。 */
    public static final int STEP_K = 100;

    private ColorTemperature() {
    }

    /**
     * 解析用户输入的 K 值。允许前后空格；空串、非数字、超出 {@code [MIN_K, MAX_K]} 一律返回
     * {@code null}，由界面统一提示「请输入 2500–10000 之间的整数」。
     */
    public static Integer parse(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (value < MIN_K || value > MAX_K) return null;
        return value;
    }

    /**
     * 把任意 K 值对齐到 {@link #STEP_K} 的整数倍并夹进 {@code [MIN_K, MAX_K]}。
     * 用于两个场合：把相机回读值整理成滑条刻度、把用户输入的小偏差收拢到合法刻度。
     */
    public static int align(int kelvin) {
        int snapped = Math.round(kelvin / (float) STEP_K) * STEP_K;
        return Math.max(MIN_K, Math.min(MAX_K, snapped));
    }
}
