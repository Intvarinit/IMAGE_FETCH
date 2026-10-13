package com.imagefetch.ptp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SensorPointMapper} 的契约测试。
 *
 * <p>{@code 0x915B TouchAfPosition} 的 X/Y 是感光器像素，而界面给的是取景帧像素；两者相差一个
 * 推流倍率（EOS RP 约 6.5×）。旧实现直接下发取景帧坐标，落点严重偏左/上，相机端表现为对焦框
 * 「完全没动」。这里守住换算与越界夹取，避免回归。
 */
public class SensorPointMapperTest {
    /** EOS RP 一档取景帧约 960×640，感光器 6240×4160。 */
    private static final int[] SENSOR = {6240, 4160};

    @Test
    public void 画面中心按感光器比例落到传感器中心() {
        int[] mapped = SensorPointMapper.toSensor(480, 320, SENSOR, 960, 640);
        assertArrayEquals(new int[]{3120, 2080}, mapped);
    }

    @Test
    public void 落点不再是取景帧坐标本身() {
        int[] mapped = SensorPointMapper.toSensor(480, 320, SENSOR, 960, 640);
        assertTrue("必须真的换算过，而不是原样下发", mapped[0] != 480 || mapped[1] != 320);
    }

    @Test
    public void 左上角映射到零() {
        int[] mapped = SensorPointMapper.toSensor(0, 0, SENSOR, 960, 640);
        assertArrayEquals(new int[]{0, 0}, mapped);
    }

    @Test
    public void 越界坐标夹到传感器边界内() {
        int[] mapped = SensorPointMapper.toSensor(960, 640, SENSOR, 960, 640);
        assertArrayEquals(new int[]{6240 - 1, 4160 - 1}, mapped);

        int[] negative = SensorPointMapper.toSensor(-50, -50, SENSOR, 960, 640);
        assertArrayEquals(new int[]{0, 0}, negative);
    }

    @Test
    public void 没有感光器分辨率时返回空() {
        assertNull(SensorPointMapper.toSensor(480, 320, null, 960, 640));
        assertNull(SensorPointMapper.toSensor(480, 320, new int[]{0, 4160}, 960, 640));
        assertNull(SensorPointMapper.toSensor(480, 320, new int[]{6240}, 960, 640));
    }

    @Test
    public void 取景帧尺寸非法时返回空() {
        assertNull(SensorPointMapper.toSensor(480, 320, SENSOR, 0, 640));
        assertNull(SensorPointMapper.toSensor(480, 320, SENSOR, 960, -1));
    }
}
