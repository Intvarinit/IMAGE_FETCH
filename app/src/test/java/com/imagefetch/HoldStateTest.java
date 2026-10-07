package com.imagefetch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * HOLD 三态契约（M2）：状态由 connected / reconnecting / manualDisconnect / suspended
 * 四个既有标志派生，这里覆盖各标志组合的推导结果。
 */
public class HoldStateTest {

    @Test
    public void manualDisconnectAlwaysReleases() {
        assertEquals(CameraSessionCoordinator.HoldState.RELEASE,
                CameraSessionCoordinator.holdStateOf(true, false, true, false));
        assertEquals(CameraSessionCoordinator.HoldState.RELEASE,
                CameraSessionCoordinator.holdStateOf(true, true, true, false));
        assertEquals(CameraSessionCoordinator.HoldState.RELEASE,
                CameraSessionCoordinator.holdStateOf(false, true, true, true));
    }

    @Test
    public void neitherConnectedNorReconnectingIsReleased() {
        assertEquals(CameraSessionCoordinator.HoldState.RELEASE,
                CameraSessionCoordinator.holdStateOf(false, false, false, false));
        // 挂起标记不能把「本来就没连上」变成 SUSPEND。
        assertEquals(CameraSessionCoordinator.HoldState.RELEASE,
                CameraSessionCoordinator.holdStateOf(false, false, false, true));
    }

    @Test
    public void connectedInForegroundHolds() {
        assertEquals(CameraSessionCoordinator.HoldState.HOLD,
                CameraSessionCoordinator.holdStateOf(true, false, false, false));
    }

    @Test
    public void connectedInBackgroundSuspends() {
        assertEquals(CameraSessionCoordinator.HoldState.SUSPEND,
                CameraSessionCoordinator.holdStateOf(true, false, false, true));
    }

    @Test
    public void inFlightReconnectCountsAsHeld() {
        assertEquals(CameraSessionCoordinator.HoldState.HOLD,
                CameraSessionCoordinator.holdStateOf(false, true, false, false));
        // 自动重连期间退到后台：仍按挂起处理，避免重连成功后立刻被误判。
        assertEquals(CameraSessionCoordinator.HoldState.SUSPEND,
                CameraSessionCoordinator.holdStateOf(false, true, false, true));
    }

    @Test
    public void connectedAndReconnectingTogetherStillDerivesFromSuspended() {
        // 理论上不该同时为真；真出现时不能崩，且以 suspended 决定 HOLD/SUSPEND。
        assertEquals(CameraSessionCoordinator.HoldState.HOLD,
                CameraSessionCoordinator.holdStateOf(true, true, false, false));
        assertEquals(CameraSessionCoordinator.HoldState.SUSPEND,
                CameraSessionCoordinator.holdStateOf(true, true, false, true));
    }
}
