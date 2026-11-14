package com.imagefetch.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 相机地址候选生成：种子优先、常见直连地址兜底、本机网段补齐，
 * 并剔除非法地址与自身地址。真实探测在设备上做，这里只锁排序与去重契约。
 */
public class CameraAddressFinderTest {

    @Test
    public void seedsComeFirstAndAreDeduplicated() {
        List<String> candidates = CameraAddressFinder.candidates(
                Collections.emptyList(),
                Arrays.asList("10.0.0.9", "10.0.0.9", " 192.168.1.2 ", "bad", null));

        assertEquals("10.0.0.9", candidates.get(0));
        // "192.168.1.2" 与内置默认地址重合，只保留一次。
        assertEquals(1, Collections.frequency(candidates, "192.168.1.2"));
        assertFalse(candidates.contains("bad"));
    }

    @Test
    public void expandsLocalSubnetButSkipsSelf() {
        List<String> candidates = CameraAddressFinder.candidates(
                Collections.singletonList("192.168.50.23"), null);

        assertTrue(candidates.contains("192.168.50.1"));
        assertTrue(candidates.contains("192.168.50.254"));
        assertFalse(candidates.contains("192.168.50.23"));
        // 1…254 共 254 个主机，去掉自身后 253 个，再加 6 个默认地址。
        assertEquals(253 + 6, candidates.size());
    }

    @Test
    public void emptyNetworkStillHasCameraDefaults() {
        List<String> candidates = CameraAddressFinder.candidates(
                Collections.emptyList(), null);

        assertEquals(6, candidates.size());
        assertTrue(candidates.contains("192.168.1.2"));
    }

    @Test
    public void normalizeAcceptsOnlyIpv4() {
        assertEquals("192.168.1.2", CameraAddressFinder.normalize(" 192.168.1.2 "));
        assertNull(CameraAddressFinder.normalize("eos_rp.local"));
        assertNull(CameraAddressFinder.normalize("192.168.1"));
        assertNull(CameraAddressFinder.normalize("192.168.1.999"));
        assertNull(CameraAddressFinder.normalize(""));
    }

    @Test
    public void multipleInterfacesMergeIntoOneCandidateList() {
        List<String> locals = new ArrayList<>(Arrays.asList("192.168.1.5", "10.1.2.5"));
        List<String> candidates = CameraAddressFinder.candidates(locals, null);

        assertTrue(candidates.contains("192.168.1.100"));
        assertTrue(candidates.contains("10.1.2.100"));
        assertEquals(candidates.size(), new java.util.LinkedHashSet<>(candidates).size());
    }
}
