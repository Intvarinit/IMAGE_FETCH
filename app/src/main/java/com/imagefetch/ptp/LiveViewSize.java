package com.imagefetch.ptp;

/**
 * Canon EOS 实时取景的画面尺寸，对应属性 {@code 0xD1B0 EVFOutputDevice} 的输出位。
 *
 * <p>该属性是位掩码：{@code 1}=机身屏(TFT)、{@code 2}=PC(Large)、{@code 4}=MOBILE(Medium)、
 * {@code 8}=MOBILE2(Small)（libgphoto2 {@code config.c:2624} 注释与
 * {@code _get/_put_Canon_LiveViewSize}，config.c:2614-2672）。相机按这个位决定往哪个输出口
 * 推多大的画面，所以写它就是真正降低「传给 App 的帧」的分辨率，而不是只在 App 端缩放。
 *
 * <p>本枚举只描述输出位；写入时由 {@code PtpIpTransport} 补上 TFT 位（{@code | 0x01}），
 * 即始终把机身屏一起点亮——libgphoto2 改画面尺寸时同样「保留 TFT 位」
 * （config.c:2669-2670）。退出取景时输出位再收回到 0x01（只给机身屏）。
 */
public enum LiveViewSize {
    /** 高：{@code 0xD1B0} 输出位 0x02（PC）。 */
    LARGE(0x02, "高"),
    /** 中：输出位 0x04（MOBILE）。 */
    MEDIUM(0x04, "中"),
    /** 低：输出位 0x08（MOBILE2）。 */
    SMALL(0x08, "低");

    /** 写入 0xD1B0 的输出位。 */
    public final int outputBit;
    /** 界面上的档位文案。 */
    public final String label;

    LiveViewSize(int outputBit, String label) {
        this.outputBit = outputBit;
        this.label = label;
    }

    /** 按序号取档位（下拉框用）；越界回落到 {@link #LARGE}。 */
    public static LiveViewSize of(int index) {
        LiveViewSize[] values = values();
        return index < 0 || index >= values.length ? LARGE : values[index];
    }
}
