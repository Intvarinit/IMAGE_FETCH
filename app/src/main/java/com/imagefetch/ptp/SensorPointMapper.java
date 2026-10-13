package com.imagefetch.ptp;

/**
 * 把取景帧像素坐标换算成 Canon {@code 0x915B TouchAfPosition} 需要的感光器像素坐标。
 *
 * <p>{@code 0x915B} 的 X/Y 量纲是<b>感光器像素</b>（0..传感器宽/高），而界面拿到的是取景帧像素
 * （约千级宽），两者相差一个由推流分辨率决定的倍数（EOS RP 约 6.5 倍）。直接下发取景帧坐标会让
 * 落点严重偏左/上，相机端表现为「对焦框几乎没动」。
 *
 * <p>取不到感光器分辨率（相机没推 {@code 0x0E} 段）时返回 {@code null}，调用方按原坐标下发并在
 * 状态栏明确标注「未换算」。
 */
public final class SensorPointMapper {
    private SensorPointMapper() {
    }

    /**
     * @param x,y                    取景帧像素坐标
     * @param sensorSize             感光器分辨率 {@code [宽, 高]}；{@code null}/非正时返回 {@code null}
     * @param frameWidth,frameHeight 取景帧像素宽高；非正时返回 {@code null}
     * @return {@code [感光器 x, 感光器 y]}，已夹到 {@code [0, 宽/高 - 1]}；无法换算返回 {@code null}
     */
    public static int[] toSensor(int x, int y, int[] sensorSize, int frameWidth, int frameHeight) {
        if (sensorSize == null || sensorSize.length < 2
                || sensorSize[0] <= 0 || sensorSize[1] <= 0
                || frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        long sx = Math.round((double) x * sensorSize[0] / frameWidth);
        long sy = Math.round((double) y * sensorSize[1] / frameHeight);
        sx = Math.max(0L, Math.min(sx, (long) sensorSize[0] - 1L));
        sy = Math.max(0L, Math.min(sy, (long) sensorSize[1] - 1L));
        return new int[]{(int) sx, (int) sy};
    }
}
