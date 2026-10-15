package com.imagefetch.ptp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link ColorTemperature} 的输入解析与刻度对齐规则。 */
public class ColorTemperatureTest {

    @Test
    public void parse接受区间内的整数值() {
        assertEquals(Integer.valueOf(5200), ColorTemperature.parse("5200"));
    }

    @Test
    public void parse容忍前后空格() {
        assertEquals(Integer.valueOf(2500), ColorTemperature.parse("  2500 "));
    }

    @Test
    public void parse对空串与null返回null() {
        assertNull(ColorTemperature.parse(""));
        assertNull(ColorTemperature.parse("   "));
        assertNull(ColorTemperature.parse(null));
    }

    @Test
    public void parse对非数字返回null() {
        assertNull(ColorTemperature.parse("abc"));
        assertNull(ColorTemperature.parse("5200K"));
        assertNull(ColorTemperature.parse("52.3"));
    }

    @Test
    public void parse对越界值返回null() {
        assertNull(ColorTemperature.parse("2499"));
        assertNull(ColorTemperature.parse("10001"));
    }

    @Test
    public void parse接受上下限本身() {
        assertEquals(Integer.valueOf(2500), ColorTemperature.parse("2500"));
        assertEquals(Integer.valueOf(10000), ColorTemperature.parse("10000"));
    }

    @Test
    public void align把非整刻度收拢到最近的百位() {
        assertEquals(5200, ColorTemperature.align(5234));
        assertEquals(5300, ColorTemperature.align(5251));
        assertEquals(5300, ColorTemperature.align(5300));
    }

    @Test
    public void align把越界值夹进上下限() {
        assertEquals(ColorTemperature.MIN_K, ColorTemperature.align(1000));
        assertEquals(ColorTemperature.MAX_K, ColorTemperature.align(20000));
    }
}
