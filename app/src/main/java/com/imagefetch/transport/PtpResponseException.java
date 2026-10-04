package com.imagefetch.transport;

import java.io.IOException;

/**
 * 相机 PTP 响应码异常（PTP/IP 与 USB/PTP 共用），供上层区分
 * “会话失效（必须重连）” 与 “可重试（写卡未完成 / 设备忙）”。
 */
public final class PtpResponseException extends IOException {
    public final int responseCode;

    public PtpResponseException(int response) {
        super(String.format("相机拒绝了 PTP 操作（0x%04X）", response));
        responseCode = response;
    }

    /** DeviceBusy(0x2019) / GeneralError(0x2006)：写卡尚未完成等可重试情形。 */
    public boolean isTransient() {
        return responseCode == 0x2019 || responseCode == 0x2006;
    }

    /** SessionNotOpen(0x2003) / InvalidTransactionID(0x2004) / SessionAlreadyOpen(0x201E)。 */
    public boolean isSessionFatal() {
        return responseCode == 0x2003
                || responseCode == 0x2004
                || responseCode == 0x201e;
    }
}
