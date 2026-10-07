package com.imagefetch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * WifiNetworkSpecifier 的启用条件（M4）：只有「系统支持（SDK 29+）且填了非空 SSID」
 * 才走系统「加入网络」对话框，其余一律回退现有自动选网路径。
 */
public class WifiRequestPolicyTest {

    @Test
    public void belowApi29NeverUsesSpecifier() {
        assertFalse(CameraSessionCoordinator.shouldUseSpecifier(26, "EOS_RP"));
        assertFalse(CameraSessionCoordinator.shouldUseSpecifier(28, "EOS_RP"));
    }

    @Test
    public void api29AndAboveUseSpecifierWhenSsidPresent() {
        assertTrue(CameraSessionCoordinator.shouldUseSpecifier(29, "EOS_RP"));
        assertTrue(CameraSessionCoordinator.shouldUseSpecifier(33, "EOS_RP"));
        assertTrue(CameraSessionCoordinator.shouldUseSpecifier(35, "EOS_RP"));
    }

    @Test
    public void blankSsidFallsBackEvenOnNewApi() {
        assertFalse(CameraSessionCoordinator.shouldUseSpecifier(29, ""));
        assertFalse(CameraSessionCoordinator.shouldUseSpecifier(29, "   "));
        assertFalse(CameraSessionCoordinator.shouldUseSpecifier(29, null));
    }

    @Test
    public void ssidIsTrimmedBeforeValidation() {
        assertTrue(CameraSessionCoordinator.isValidSsid(" EOS_RP "));
        assertTrue(CameraSessionCoordinator.shouldUseSpecifier(29, " EOS_RP "));
    }

    @Test
    public void isValidSsidRejectsEmptyAndBlankOnly() {
        assertFalse(CameraSessionCoordinator.isValidSsid(null));
        assertFalse(CameraSessionCoordinator.isValidSsid(""));
        assertFalse(CameraSessionCoordinator.isValidSsid("   "));
        assertFalse(CameraSessionCoordinator.isValidSsid("\t\n"));
    }

    @Test
    public void isValidSsidAcceptsOrdinaryNames() {
        assertTrue(CameraSessionCoordinator.isValidSsid("EOS_RP"));
        assertTrue(CameraSessionCoordinator.isValidSsid(" EOS_RP "));
        // 长度不在这里校验：是否合法由系统对话框与相机自身判定。
        assertTrue(CameraSessionCoordinator.isValidSsid(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }
}
