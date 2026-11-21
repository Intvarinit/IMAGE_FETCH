#include <jni.h>

#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "libraw/libraw.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {

constexpr const char *kLogTag = "ImageFetchRaw";

void throwIOException(JNIEnv *env, const std::string &message) {
    jclass clazz = env->FindClass("java/io/IOException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
    }
}

std::string librawError(int code) {
    const char *text = libraw_strerror(code);
    return text == nullptr ? "RAW 解码失败" : text;
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

/**
 * LibRaw 显影参数：与机内 JPG 尽量接近——相机白平衡、sRGB、AHD 去马赛克、
 * 8 位输出。不做额外曝光/色彩风格调整，避免偏离用户拍摄时的设定。
 */
void configure(libraw_data_t *raw) {
    raw->params.output_bps = 8;
    raw->params.output_color = 1;  // sRGB
    raw->params.use_camera_wb = 1;
    raw->params.use_auto_wb = 0;
    raw->params.no_auto_bright = 0;
    raw->params.user_qual = 3;  // AHD
    raw->params.output_tiff = 0;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_imagefetch_raw_RawDeveloper_nativeDevelop(
        JNIEnv *env, jclass, jstring rawPathValue, jstring jpegPathValue, jint quality) {
    const std::string rawPath = jstringToUtf8(env, rawPathValue);
    const std::string jpegPath = jstringToUtf8(env, jpegPathValue);
    if (rawPath.empty() || jpegPath.empty()) {
        throwIOException(env, "RAW 显影路径为空。");
        return nullptr;
    }

    libraw_data_t *raw = libraw_init(0);
    if (raw == nullptr) {
        throwIOException(env, "LibRaw 初始化失败（内存不足）。");
        return nullptr;
    }
    configure(raw);

    int code = libraw_open_file(raw, rawPath.c_str());
    if (code != LIBRAW_SUCCESS) {
        throwIOException(env, "打开 RAW 失败：" + librawError(code));
        libraw_close(raw);
        return nullptr;
    }
    code = libraw_unpack(raw);
    if (code != LIBRAW_SUCCESS) {
        throwIOException(env, "解包 RAW 失败：" + librawError(code));
        libraw_close(raw);
        return nullptr;
    }
    code = libraw_dcraw_process(raw);
    if (code != LIBRAW_SUCCESS) {
        throwIOException(env, "显影 RAW 失败：" + librawError(code));
        libraw_close(raw);
        return nullptr;
    }

    int imageError = LIBRAW_SUCCESS;
    libraw_processed_image_t *image = libraw_dcraw_make_mem_image(raw, &imageError);
    if (image == nullptr || imageError != LIBRAW_SUCCESS) {
        throwIOException(env, "生成全尺寸图像失败：" + librawError(imageError));
        if (image != nullptr) libraw_dcraw_clear_mem(image);
        libraw_close(raw);
        return nullptr;
    }
    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 8) {
        throwIOException(env, "RAW 显影结果不是 8 位 RGB 图像。");
        libraw_dcraw_clear_mem(image);
        libraw_close(raw);
        return nullptr;
    }

    int jpegQuality = static_cast<int>(quality);
    if (jpegQuality < 60) jpegQuality = 60;
    if (jpegQuality > 100) jpegQuality = 100;
    const int written = stbi_write_jpg(
            jpegPath.c_str(),
            static_cast<int>(image->width),
            static_cast<int>(image->height),
            3,
            image->data,
            jpegQuality);
    const int width = static_cast<int>(image->width);
    const int height = static_cast<int>(image->height);

    libraw_dcraw_clear_mem(image);
    libraw_close(raw);

    if (written == 0) {
        std::remove(jpegPath.c_str());
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JPEG 编码失败：%s", jpegPath.c_str());
        throwIOException(env, "JPEG 编码失败（存储空间不足或路径不可写）。");
        return nullptr;
    }

    __android_log_print(
            ANDROID_LOG_INFO, kLogTag, "RAW 显影完成：%d x %d -> %s", width, height, jpegPath.c_str());
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    jint values[2] = {width, height};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}
