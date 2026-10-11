"""景深合成真机自检（临时文件，验收后连同 Java 侧入口一起删除）。

回答两件事：
1. 手机上 Chaquopy 的 Python 运行时能不能起来，numpy / cv2 能不能导入；
2. 景深合成的核心链路（逐像素清晰度判据 → 取最锐像素）能不能得出正确结果。

第 2 点用一组人造的「对焦包围」样张来验证：三张图各自只有一条横条是对上焦的，
其余部分被模糊；合成后三条都应恢复清晰。这样既不需要相机、也不需要真实照片，
就能一眼看出算法是否真的跑对了——而不是只看「能 import」这种弱证据。
"""

import sys
import traceback

PANEL_WIDTH = 260
PANEL_HEIGHT = 200
# 模拟三个对焦平面（画面上中下三条）
BANDS = 3


def _one_line(error):
    """把异常压成一行，方便塞进报告里显示。"""
    return traceback.format_exception_only(type(error), error)[0].strip()


def report():
    """运行时报告：版本 + 原生库 + 一条最小算子链路。任何一步失败都如实写出来。"""
    lines = ["python = %s" % sys.version.replace("\n", " ")]

    try:
        import numpy
        lines.append("numpy  = %s" % numpy.__version__)
    except Exception as error:
        lines.append("numpy  = 导入失败：%s" % _one_line(error))
        return "\n".join(lines)

    try:
        import cv2
    except Exception as error:
        lines.append("cv2    = 导入失败：%s" % _one_line(error))
        return "\n".join(lines)
    lines.append("cv2    = %s" % cv2.__version__)

    try:
        cv2.SIFT_create()
        lines.append("sift   = 可用")
    except Exception:
        lines.append("sift   = 不可用")

    try:
        import numpy
        rng = numpy.random.default_rng(0)
        sample = rng.integers(0, 256, size=(256, 256)).astype(numpy.uint8)
        lap = cv2.Laplacian(cv2.GaussianBlur(sample, (5, 5), 0), cv2.CV_64F)
        lines.append("算子   = 正常（拉普拉斯均值 %.4f）" % float(numpy.absolute(lap).mean()))
    except Exception as error:
        lines.append("算子   = 失败：%s" % _one_line(error))

    return "\n".join(lines)


def _scene():
    """造一张本身处处有细节的底图：细节越密，清晰与模糊的差别越明显。"""
    import cv2
    import numpy

    rng = numpy.random.default_rng(7)
    base = rng.integers(70, 200, size=(PANEL_HEIGHT, PANEL_WIDTH, 3)).astype(numpy.uint8)
    band_height = PANEL_HEIGHT // BANDS
    for index in range(BANDS):
        y = index * band_height + band_height // 2
        cv2.putText(base, "BAND %d" % (index + 1), (10, y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
    return base


def _shots():
    """三张样张：第 i 张只有第 i 条对上焦，其余部分模糊。"""
    import cv2

    base = _scene()
    blurred = cv2.GaussianBlur(base, (0, 0), 6)
    band_height = PANEL_HEIGHT // BANDS

    shots = []
    for index in range(BANDS):
        top = index * band_height
        bottom = PANEL_HEIGHT if index == BANDS - 1 else (index + 1) * band_height
        shot = blurred.copy()
        shot[top:bottom] = base[top:bottom]
        shots.append(shot)
    return shots


def _sharpness_map(image):
    """逐像素清晰度：拉普拉斯绝对值的局部平滑，越大越锐。"""
    import cv2
    import numpy

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    lap = numpy.absolute(cv2.Laplacian(gray, cv2.CV_64F))
    return cv2.GaussianBlur(lap, (0, 0), 3)


def _stack(shots):
    """景深合成：每个像素取所有样张里最锐的那一张。"""
    import numpy

    best = numpy.argmax(numpy.stack([_sharpness_map(s) for s in shots]), axis=0)
    out = shots[0].copy()
    for index, shot in enumerate(shots):
        out[best == index] = shot[best == index]
    return out


def _band_sharpness(image):
    """把画面按条统计清晰度，用来判读合成是否真的逐条取到了最锐像素。"""
    import cv2
    import numpy

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    lap = numpy.absolute(cv2.Laplacian(gray, cv2.CV_64F))
    band_height = lap.shape[0] // BANDS
    values = []
    for index in range(BANDS):
        top = index * band_height
        bottom = lap.shape[0] if index == BANDS - 1 else (index + 1) * band_height
        values.append(float(lap[top:bottom].mean()))
    return values


def _label(image, text):
    """给每格加一条白底标题，方便在手机上对照。"""
    import cv2
    import numpy

    strip = numpy.full((26, image.shape[1], 3), 255, numpy.uint8)
    cv2.putText(strip, text, (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1)
    return numpy.concatenate([strip, image], axis=0)


def demo():
    """跑一遍合成，返回 (对比图 PNG 字节, 判读文字)。

    对比图从左到右是「样张 1 | 样张 2 | 样张 3 | 合成结果」，四条都清晰才算通过。
    """
    import cv2
    import numpy

    shots = _shots()
    stacked = _stack(shots)

    panels = [_label(shot, "shot %d (band %d sharp)" % (i + 1, i + 1))
              for i, shot in enumerate(shots)]
    panels.append(_label(stacked, "stacked"))
    sheet = numpy.concatenate(panels, axis=1)

    ok, buffer = cv2.imencode(".png", sheet)
    if not ok:
        raise RuntimeError("PNG 编码失败")

    lines = ["合成结果：%d x %d" % (sheet.shape[1], sheet.shape[0])]
    stacked_bands = _band_sharpness(stacked)
    shot_bands = [_band_sharpness(shot) for shot in shots]
    for index in range(BANDS):
        best = max(shot_bands[n][index] for n in range(BANDS))
        picked = stacked_bands[index]
        verdict = "取到最锐" if best > 0 and picked >= best * 0.95 else "未取到最锐"
        lines.append("第 %d 条：合成 %.2f / 最佳样张 %.2f → %s"
                     % (index + 1, picked, best, verdict))
    lines.append("判读：三条都应显示「取到最锐」，合成图三条都应清晰。")
    return (buffer.tobytes(), "\n".join(lines))