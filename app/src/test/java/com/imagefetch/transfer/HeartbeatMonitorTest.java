package com.imagefetch.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 连续心跳失败的离线判定契约。 */
public class HeartbeatMonitorTest {
    @Test
    public void goesOfflineOnlyOnTheThirdConsecutiveFailure() {
        HeartbeatMonitor monitor = new HeartbeatMonitor(3);
        assertFalse(monitor.recordProbeResult(false));
        assertFalse(monitor.recordProbeResult(false));
        assertTrue(monitor.recordProbeResult(false));
        assertEquals(0, monitor.missedCount());
    }

    @Test
    public void aSuccessfulProbeResetsTheFailureStreak() {
        HeartbeatMonitor monitor = new HeartbeatMonitor(3);
        monitor.recordProbeResult(false);
        monitor.recordProbeResult(false);
        assertFalse(monitor.recordProbeResult(true));
        assertEquals(0, monitor.missedCount());
        assertFalse(monitor.recordProbeResult(false));
        assertFalse(monitor.recordProbeResult(false));
        assertTrue(monitor.recordProbeResult(false));
    }

    @Test
    public void thresholdIsClampedToAtLeastOne() {
        assertTrue(new HeartbeatMonitor(0).recordProbeResult(false));
        assertTrue(new HeartbeatMonitor(-4).recordProbeResult(false));
    }

    @Test
    public void resetClearsTheStreak() {
        HeartbeatMonitor monitor = new HeartbeatMonitor(3);
        monitor.recordProbeResult(false);
        monitor.recordProbeResult(false);
        monitor.reset();
        assertEquals(0, monitor.missedCount());
        assertFalse(monitor.recordProbeResult(false));
    }
}
