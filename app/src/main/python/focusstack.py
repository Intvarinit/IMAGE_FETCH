"""真实照片的景深合成：把一组「对焦包围」照片合成成一张处处清晰的照片。

流程三步：

1. **配准**：机内对焦包围是在不同对焦位置上拍的，机身未必锁死，画面前后景的位移也不一致。
   所以先对每张图求「到参考帧的单应矩阵」（SIFT 特征 + RANSAC），再把它们变换到同一个坐标系。
   参考帧取中间那一张——前后景的位移都最小，warp 后越界最少。
2. **融合**：逐像素算清晰度（拉普拉斯绝对值的局部平滑，越大越锐），每个像素取所有样张里最锐的那张。
3. **编码**：合成图编成 JPEG 字节返回。

**融合在全分辨率上进行**（2600 万像素也照做），所以内存不能靠缩图来省，改用两条纪律压住峰值：

* 单应矩阵是**等比缩放不变**的：配准在缩小的图上算（快、特征点少），算出的矩阵原样用在原图上，
  结果与在原图上配准等价。所以配准图是缩小的，融合图是原尺寸。
* 融合**分两条横向条带**做：清晰度打分一遍、按最优索引取像素一遍。任意时刻内存里只有
  一张原图 + 一张打分/索引图 + 一条带，不随张数线性增长。

配准失败的样张会被**丢弃**（不是拿没对齐的原图顶上——那样只会把合成图弄糊），报告里逐张说明。
"""

import time
import traceback

# 配准用的工作分辨率。单应矩阵只要几十个可靠特征点就够，缩到几百万像素算得又快又稳；
# 它是等比缩放的，把矩阵搬回原图不损失精度（缩放是解析的，不是二次估计）。
ALIGN_PIXELS = 3_000_000
# 融合时的条带高度（像素）。条带越小峰值内存越低，但 warp 调用次数越多。
STRIP_ROWS = 512
# 参与合成的最大样张数。融合阶段「哪张最锐」的索引图用 uint8 装下标，所以硬上限是 256 张：
# 这是数据结构的边界，不是画质取舍。除此之外没有张数限制——配准与融合都是逐张处理，
# 峰值内存与张数无关，只是耗时随张数线性增长。
MAX_SHOTS = 256
# 合成结果的 JPEG 质量。
JPEG_QUALITY = 95
# 回给界面预览用的缩略图长边（预览只是给人看构图，不必是原图；也避免 Java 端解码大图）。
PREVIEW_MAX_PX = 1280
# RANSAC 重投影误差阈值（以配准分辨率为准），单位像素。
RANSAC_REPROJ_THRESHOLD = 3.0
# 判定配准成功所需的最少内点；低于此值认为这张没对上，丢弃。
MIN_INLIERS = 12


def _one_line(error):
    """把异常压成一行，方便塞进报告里显示。"""
    return traceback.format_exception_only(type(error), error)[0].strip()


def _basename(path):
    import os

    return os.path.basename(path)


def _gray(image):
    import cv2

    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)


def _load(path):
    """读一张图。cv2.imread 对 JPEG 会按 EXIF 方向自动摆正，同一批样张方向一致。"""
    import cv2

    image = cv2.imread(path, cv2.IMREAD_COLOR)
    if image is None:
        raise RuntimeError("读不出图像（格式不支持、文件损坏或路径不存在）")
    return image


def _size_of(path):
    """只取尺寸，不解码整幅：用 PIL 的惰性读取对 2600 万像素图几乎瞬时。"""
    import cv2

    # cv2 没有「只读尺寸」的接口；这一处用 imread 拿尺寸的代价是可接受的（每张只做一次）。
    image = cv2.imread(path, cv2.IMREAD_REDUCED_COLOR_8)
    if image is None:
        raise RuntimeError("读不出图像（格式不支持、文件损坏或路径不存在）")
    return image.shape[1] * 8, image.shape[0] * 8


def _detector():
    """优先 SIFT（精度高），拿不到就退 ORB。返回 (检测器, 距离度量, 名字)。

    特征数上限是为了控时间与内存：单应矩阵只要几十个可靠点就够，几万个点纯属浪费。
    """
    import cv2

    try:
        return cv2.SIFT_create(nfeatures=4000), cv2.NORM_L2, "SIFT"
    except Exception:
        return cv2.ORB_create(4000), cv2.NORM_HAMMING, "ORB"


def _homography(detector, norm, reference_gray, moving_gray):
    """求 moving → reference 的单应矩阵。返回 (矩阵, 内点数, 良匹配数)；配准不成立时抛异常。"""
    import cv2
    import numpy

    ref_points, ref_desc = detector.detectAndCompute(reference_gray, None)
    mov_points, mov_desc = detector.detectAndCompute(moving_gray, None)
    if ref_desc is None or mov_desc is None or len(ref_points) < 8 or len(mov_points) < 8:
        raise RuntimeError("特征点不足（参考 %d / 本张 %d）"
                           % (len(ref_points), len(mov_points)))

    matcher = cv2.BFMatcher(norm)
    pairs = matcher.knnMatch(mov_desc, ref_desc, k=2)
    good = [pair[0] for pair in pairs
            if len(pair) == 2 and pair[0].distance < 0.75 * pair[1].distance]
    if len(good) < 8:
        raise RuntimeError("可靠匹配仅 %d 对，不足以配准" % len(good))

    src = numpy.float32([mov_points[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
    dst = numpy.float32([ref_points[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
    matrix, mask = cv2.findHomography(src, dst, cv2.RANSAC, RANSAC_REPROJ_THRESHOLD)
    if matrix is None:
        raise RuntimeError("RANSAC 未求出有效单应矩阵")
    inliers = int(mask.sum()) if mask is not None else 0
    if inliers < MIN_INLIERS:
        raise RuntimeError("内点仅 %d 个（少于 %d），判定配准不成立" % (inliers, MIN_INLIERS))
    return matrix, inliers, len(good)


def _shifted(matrix, y0):
    """把「源 → 参考帧」的单应矩阵平移，得到「源 → 参考帧第 y0 行起的条带」。"""
    import numpy

    shift = numpy.array([[1.0, 0.0, 0.0],
                         [0.0, 1.0, -float(y0)],
                         [0.0, 0.0, 1.0]])
    return shift.dot(matrix)


def _aligned_strip(matrix, image, width, y0, y1):
    """取出「已经对齐到参考帧」的一条带。边缘复制填充，避免出现吸黑的硬边。"""
    import cv2

    return cv2.warpPerspective(
        image, _shifted(matrix, y0), (width, y1 - y0),
        flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def _score_strip(strip):
    """一条带的逐像素清晰度：拉普拉斯绝对值的局部平滑，压成 uint16 省一半内存。

    灰度差分的绝对值上限约 2040（8 邻域拉普拉斯 × 255），uint16 装得下，量化损失可忽略。
    """
    import cv2
    import numpy

    lap = numpy.absolute(cv2.Laplacian(_gray(strip), cv2.CV_32F))
    smoothed = cv2.GaussianBlur(lap, (0, 0), 3.0)
    return numpy.clip(smoothed, 0.0, 65535.0).astype(numpy.uint16)


def _fuse(paths, matrices, width, height, note):
    """逐像素取最锐。分两遍、每遍按条带推进，内存不随张数增长。

    :param note: 单行回调，用于把进度写进报告（可为 None）
    """
    import cv2
    import numpy

    best_score = numpy.zeros((height, width), numpy.uint16)
    best_index = numpy.zeros((height, width), numpy.uint8)

    rows = range(0, height, STRIP_ROWS)
    # 第一遍：给每个像素记下「哪一张最锐」。
    for index, (path, matrix) in enumerate(zip(paths, matrices)):
        image = _load_same_size(path, width, height)
        if note:
            note("对齐与打分 %d / %d…" % (index + 1, len(paths)))
        for y0 in rows:
            y1 = min(height, y0 + STRIP_ROWS)
            strip = _aligned_strip(matrix, image, width, y0, y1)
            score = _score_strip(strip)
            window = best_score[y0:y1]
            take = score > window
            window[take] = score[take]
            best_index[y0:y1][take] = index
        del image

    # 第二遍：按最优索引把像素拼起来。原图再读一遍，避免同时抱住所有原图。
    output = numpy.empty((height, width, 3), numpy.uint8)
    for index, (path, matrix) in enumerate(zip(paths, matrices)):
        if not numpy.any(best_index == index):
            continue
        image = _load_same_size(path, width, height)
        if note:
            note("合成像素 %d / %d…" % (index + 1, len(paths)))
        for y0 in rows:
            y1 = min(height, y0 + STRIP_ROWS)
            select = best_index[y0:y1] == index
            if not select.any():
                continue
            strip = _aligned_strip(matrix, image, width, y0, y1)
            output[y0:y1][select] = strip[select]
        del image
    return output


def _load_same_size(path, width, height):
    """读原图并保证尺寸等于参考帧。同机同批本应一致，尺寸不同时才真的缩放。"""
    import cv2

    image = _load(path)
    if image.shape[1] != width or image.shape[0] != height:
        image = cv2.resize(image, (width, height), interpolation=cv2.INTER_AREA)
    return image


def stack(paths, progress=None):
    """把一组对焦包围照片合成成一张全清晰图。

    :param paths: 照片文件路径序列（按拍摄顺序，最好是先近后远）
    :param progress: 可选，接受一行中文进度的回调（无参也可省略）
    :return: ``[合成图 JPEG 字节, 预览 PNG 字节, 报告文字]``；
             任何一步失败都抛异常，由调用方转成可读文本
    """
    started = time.time()
    lines = []

    def note(message):
        if progress is None:
            return
        try:
            progress(message)
            return
        except TypeError:
            # Java 侧传进来的是 Consumer 的代理对象，不是 Python 函数，直接调用会
            # TypeError；退回它的 accept()。进度只是让人知道没卡住，任何异常都不该中断合成。
            try:
                progress.accept(message)
            except Exception:
                pass
        except Exception:
            pass

    # Java 侧传来的路径是 java.util.List 的代理对象，不是 Python 序列——`list(paths)` 会直接
    # 抛 TypeError: 'ArrayList' object is not iterable。所以按 Java 的 size()/get() 取值；
    # 真在 Python 里直接调用（自检脚本等）时走普通序列那条路。
    if hasattr(paths, "size") and hasattr(paths, "get"):
        paths = [str(paths.get(index)) for index in range(paths.size())]
    else:
        paths = [str(path) for path in list(paths)]
    if len(paths) < 2:
        raise RuntimeError("景深合成至少需要 2 张照片，本次只收到 %d 张。" % len(paths))

    used = paths[:MAX_SHOTS]
    if len(paths) > MAX_SHOTS:
        lines.append("样张超过上限 %d 张，忽略后面的 %d 张。"
                     % (MAX_SHOTS, len(paths) - MAX_SHOTS))

    import cv2
    import math
    import numpy

    # ── 参考帧（中间那张）与配准比例 ──
    # 先做一遍「读得出吗」的廉价探测：_size_of 只解 1/8 缩图，不解全图。
    # 这一步只用来筛掉坏文件、定下参考帧，不把整批图抱在内存里。
    readable = []
    for path in used:
        try:
            _size_of(path)
        except Exception as error:
            lines.append("跳过 %s：%s" % (_basename(path), _one_line(error)))
            continue
        readable.append(path)
    if len(readable) < 2:
        raise RuntimeError("可用的照片不足 2 张，无法合成。\n" + "\n".join(lines))

    # 参考帧取中间那张——前后景的位移都最小，warp 后越界最少。
    reference = len(readable) // 2
    full_width, full_height = _size_of(readable[reference])
    scale = min(1.0, math.sqrt(ALIGN_PIXELS / float(full_width * full_height)))
    align_size = (max(1, int(round(full_width * scale))),
                  max(1, int(round(full_height * scale))))
    lines.append("原始分辨率 %d x %d，合成在全分辨率上进行；配准在 %d x %d 图上算%s。"
                 % (full_width, full_height, align_size[0], align_size[1],
                    "（已缩图）" if scale < 1.0 else "（与原图同尺寸）"))

    # ── 逐张求单应矩阵 ──
    # 只有参考帧的缩图常驻内存，其余样张一律「载入 → 缩图 → 求矩阵 → 立刻放掉」。
    # 峰值内存因此与张数无关——这是「不设张数上限」的前提：整批缩图一起抱住的话，
    # 40 张 2600 万像素样张光缩图就要几百 MB。
    note("准备配准…")
    detector, norm, detector_name = _detector()
    try:
        reference_gray = _gray(
            cv2.resize(_load(readable[reference]), align_size, interpolation=cv2.INTER_AREA))
    except Exception as error:
        raise RuntimeError("参考帧 %s 读不出：%s"
                           % (_basename(readable[reference]), _one_line(error)))

    paths_ok = [readable[reference]]
    matrices = [numpy.eye(3)]
    aligned_names = ["%s（参考帧）" % _basename(readable[reference])]
    for index, path in enumerate(readable):
        if index == reference:
            continue
        try:
            small = cv2.resize(_load(path), align_size, interpolation=cv2.INTER_AREA)
        except Exception as error:
            lines.append("跳过 %s：%s" % (_basename(path), _one_line(error)))
            continue
        try:
            matrix, inliers, good = _homography(
                detector, norm, reference_gray, _gray(small))
        except Exception as error:
            lines.append("丢弃 %s：%s" % (_basename(path), _one_line(error)))
            del small
            continue
        del small
        paths_ok.append(path)
        matrices.append(matrix)
        aligned_names.append("%s（%s 内点 %d / 良匹配 %d）"
                             % (_basename(path), detector_name, inliers, good))
        note("配准 %d / %d…" % (index + 1, len(readable)))

    # 参考帧的灰度缩图用完就放掉，进融合前把峰值让出来。
    del reference_gray

    if len(paths_ok) < 2:
        raise RuntimeError("配准后只剩 1 张可用，无法合成。\n" + "\n".join(lines))

    # ── 全分辨率融合 ──
    fused = _fuse(paths_ok, matrices, full_width, full_height, note)
    lines.append("参与合成 %d 张，特征检测用 %s：" % (len(paths_ok), detector_name))
    for name in aligned_names:
        lines.append("· " + name)

    # ── 编码：全分辨率 JPEG + 小预览 ──
    note("编码合成图…")
    ok, jpeg_buffer = cv2.imencode(
        ".jpg", fused, [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
    if not ok:
        raise RuntimeError("JPEG 编码失败")

    preview_scale = min(1.0, PREVIEW_MAX_PX / float(max(full_width, full_height)))
    if preview_scale < 1.0:
        preview = cv2.resize(
            fused,
            (max(1, int(round(full_width * preview_scale))),
             max(1, int(round(full_height * preview_scale)))),
            interpolation=cv2.INTER_AREA)
    else:
        preview = fused
    ok, png_buffer = cv2.imencode(".png", preview)
    if not ok:
        raise RuntimeError("预览图编码失败")

    lines.append("合成完成：%d x %d（%d 万像素），耗时 %.1f 秒。"
                 % (full_width, full_height,
                    round(full_width * full_height / 10000.0), time.time() - started))
    return [jpeg_buffer.tobytes(), png_buffer.tobytes(), "\n".join(lines)]