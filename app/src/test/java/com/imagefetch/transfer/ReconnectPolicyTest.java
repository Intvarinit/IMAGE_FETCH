package com.imagefetch.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 退避序列与保活参数的契约（对齐 ZENCHE PTPIP_PROTOCOL.md §9 的 B2 策略）。 */
public class ReconnectPolicyTest {
    @Test
    public void backoffFollowsTheCappedSequence() {
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(1));
        assertEquals(2000L, ReconnectPolicy.backoffDelayMs(2));
        assertEquals(4000L, ReconnectPolicy.backoffDelayMs(3));
        assertEquals(8000L, ReconnectPolicy.backoffDelayMs(4));
        assertEquals(16000L, ReconnectPolicy.backoffDelayMs(5));
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(6));
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(7));
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(99));
    }

    @Test
    public void invalidAttemptsClampToTheFirstDelay() {
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(0));
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(-5));
    }

    @Test
    public void heartbeatParametersMatchTheProtocolDoc() {
        assertEquals(5000L, ReconnectPolicy.HEARTBEAT_INTERVAL_MS);
        assertEquals(3, ReconnectPolicy.OFFLINE_THRESHOLD);
    }
}
