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

    /**
     * DeviceBusy(0x2019) / GeneralError(0x2006) / ObjectNotReady(0xA102) /
     * MemoryStatusNotReady(0xA106)：写卡或机内合成尚未完成等可重试情形。
     *
     * <p>0xA106 是 Canon EOS 的「内存状态未就绪」：HDR、机内合成、连拍写卡期间
     * 相机屏幕会显示 busy，此时任何读卡/取对象命令都可能被它顶回来。它不是永久失败，
     * 等相机处理完再发就能成功。
     */
    public boolean isTransient() {
        return responseCode == 0x2019
                || responseCode == 0x2006
                || responseCode == 0xa102
                || responseCode == 0xa106;
    }

    /** SessionNotOpen(0x2003) / InvalidTransactionID(0x2004) / SessionAlreadyOpen(0x201E)。 */
    public boolean isSessionFatal() {
        return responseCode == 0x2003
                || responseCode == 0x2004
                || responseCode == 0x201e;
    }
}
