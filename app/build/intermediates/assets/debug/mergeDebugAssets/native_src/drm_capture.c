/*
 * drm_capture.c
 * 直接通过 DRM/KMS API 读取 GPU 帧缓冲，绕过 SurfaceFlinger 保护层机制。
 *
 * 原理：
 * 1. 打开 /dev/dri/card0 (DRM master)
 * 2. 通过 drmModeGetResources → CRTC → Framebuffer 获取当前显示帧
 * 3. 使用 drmPrimeHandleToFD + mmap 直接映射 GPU 内存到用户空间
 * 4. 写出 PPM/RAW 格式图像
 *
 * 此方法在 GPU驱动层面读取，完全绕过 Android 框架和 SurfaceFlinger 安全层。
 *
 * 编译: gcc -O2 -o drm_capture drm_capture.c -ldrm
 * 用法: drm_capture <output.ppm>
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <stdint.h>

/*── 内联必要的DRM 结构体（避免依赖 drm.h 头文件）── */

#define DRM_IOCTL_BASE 'd'
#define DRM_IO(nr)   _IO(DRM_IOCTL_BASE,(nr))
#define DRM_IOR(nr,type) _IOR(DRM_IOCTL_BASE,(nr),type)
#define DRM_IOW(nr,type) _IOW(DRM_IOCTL_BASE,(nr),type)
#define DRM_IOWR(nr,type) _IOWR(DRM_IOCTL_BASE,(nr),type)

#define DRM_IOCTL_MODE_GETRESOURCES DRM_IOWR(0xA0, struct drm_mode_card_res)
#define DRM_IOCTL_MODE_GETCRTC      DRM_IOWR(0xA1, struct drm_mode_crtc)
#define DRM_IOCTL_MODE_GETFB        DRM_IOWR(0xAD, struct drm_mode_fb_cmd)
#define DRM_IOCTL_GEM_MMAP          DRM_IOWR(0x03, struct drm_gem_map)
#define DRM_IOCTL_MODE_MAP_DUMB     DRM_IOWR(0xB3, struct drm_mode_map_dumb)
#define DRM_IOCTL_PRIME_HANDLE_TO_FD DRM_IOWR(0x2d, struct drm_prime_handle)

struct drm_mode_card_res {
    uint64_t fb_id_ptr;
    uint64_t crtc_id_ptr;
    uint64_t connector_id_ptr;
    uint64_t encoder_id_ptr;
    uint32_t count_fbs;
    uint32_t count_crtcs;
    uint32_t count_connectors;
    uint32_t count_encoders;
    uint32_t min_width, max_width;
    uint32_t min_height, max_height;
};

struct drm_mode_crtc {
    uint64_t set_connectors_ptr;
    uint32_t count_connectors;
    uint32_t crtc_id;
    uint32_t fb_id;
    uint32_t x, y;
    uint32_t gamma_size;
    uint32_t mode_valid;
    char mode[292]; /* drm_mode_modeinfo */
};

struct drm_mode_fb_cmd {
    uint32_t fb_id;
    uint32_t width, height;
    uint32_t pitch;
    uint32_t bpp;
    uint32_t depth;
    uint32_t handle;
};

struct drm_mode_map_dumb {
    uint32_t handle;
    uint32_t pad;
    uint64_t offset;
};

struct drm_prime_handle {
    uint32_t handle;
    uint32_t flags;
    int32_t fd;
};

/* ── 写PPM 格式（无需libpng）── */
static int write_ppm(const char *path, int w, int h, const uint8_t *data, int bpp) {
    FILE *f = fopen(path, "wb");
    if (!f) { perror("fopen output"); return -1; }
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const uint8_t *px = data + y * w * (bpp/8) + x * (bpp/8);
            uint8_t r, g, b;
            if (bpp == 32) {
                /* XRGB8888: byte order B G R X */
                b = px[0]; g = px[1]; r = px[2];
            } else if (bpp == 16) {
                /* RGB565 */
                uint16_t v = ((uint16_t)px[1] << 8) | px[0];
                r = ((v >> 11) & 0x1F) << 3;
                g = ((v >> 5) & 0x3F) << 2;
                b = (v & 0x1F) << 3;
            } else {
                r = g = b = px[0];
            }
            fputc(r, f); fputc(g, f); fputc(b, f);
        }
    }
    fclose(f);
    return 0;
}

int main(int argc, char *argv[]) {
    const char *out = argc > 1 ? argv[1] : "/sdcard/drm_out.ppm";
    int fd = -1;

    /* 尝试多个 DRM 设备 */
    const char *drm_devs[] = {
        "/dev/dri/card0", "/dev/dri/card1",
        "/dev/dri/renderD128", "/dev/graphics/card0",
        NULL
    };

    for (int i = 0; drm_devs[i]; i++) {
        fd = open(drm_devs[i], O_RDWR);
        if (fd >= 0) {
            fprintf(stderr, "[drm_capture] opened %s\n", drm_devs[i]);
            break;
        }
    }
    if (fd < 0) {
        /* 备用：/dev/graphics/fb0 直接读取（不需要DRM） */
        fprintf(stderr, "[drm_capture] No DRM device, trying fb0\n");
        int fb = open("/dev/graphics/fb0", O_RDONLY);
        if (fb < 0) { fprintf(stderr, "No fb0 either\n"); return 1; }

        /* 读取 fb0 信息 */
        /* 假设 1080x2400RGBX 作为 fallback */
        struct {
            uint32_t xres, yres, xres_virtual, yres_virtual;
            uint32_t xoffset, yoffset;
            uint32_t bits_per_pixel;
            uint32_t grayscale;
        } vinfo = {1080, 2400, 1080, 4800, 0, 0, 32, 0};

        /*ioctl FBIOGET_VSCREENINFO = 0x4600 */
        ioctl(fb, 0x4600, &vinfo);
        int w = vinfo.xres, h = vinfo.yres;
        if (w <= 0 || w > 4096) w = 1080;
        if (h <= 0 || h > 8192) h = 2400;
        int stride = w * 4;
        size_t sz = (size_t)h * stride;
        uint8_t *buf = malloc(sz);
        if (!buf) { close(fb); return 1; }
        if (read(fb, buf, sz) > 1000) {
            write_ppm(out, w, h, buf, 32);
            fprintf(stderr, "[drm_capture] fb0 OK: %dx%d -> %s\n", w, h, out);
            free(buf); close(fb); return 0;
        }
        free(buf); close(fb); return 1;
    }

    /* 获取 DRM 资源 */
    struct drm_mode_card_res res = {0};
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res)) {
        perror("GETRESOURCES"); close(fd); return 1;
    }
    if (res.count_crtcs == 0) {
        fprintf(stderr, "No CRTCs\n"); close(fd); return 1;
    }

    /* 分配 CRTC ID数组 */
    uint32_t *crtc_ids = calloc(res.count_crtcs, sizeof(uint32_t));
    res.crtc_id_ptr = (uint64_t)(uintptr_t)crtc_ids;
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res)) {
        perror("GETRESOURCES2"); free(crtc_ids); close(fd); return 1;
    }

    /* 遍历 CRTC，找到激活的帧缓冲 */
    uint32_t fb_id = 0, fb_w = 0, fb_h = 0, fb_pitch = 0, fb_bpp = 32;
    uint32_t fb_handle = 0;

    for (uint32_t i = 0; i < res.count_crtcs; i++) {
        struct drm_mode_crtc crtc = {0};
        crtc.crtc_id = crtc_ids[i];
        if (ioctl(fd, DRM_IOCTL_MODE_GETCRTC, &crtc)) continue;
        if (crtc.fb_id == 0) continue;

        struct drm_mode_fb_cmd fbcmd = {0};
        fbcmd.fb_id = crtc.fb_id;
        if (ioctl(fd, DRM_IOCTL_MODE_GETFB, &fbcmd)) continue;

        fb_id = fbcmd.fb_id;
        fb_w = fbcmd.width;
        fb_h = fbcmd.height;
        fb_pitch = fbcmd.pitch;
        fb_bpp = fbcmd.bpp ? fbcmd.bpp : 32;
        fb_handle = fbcmd.handle;
        fprintf(stderr, "[drm_capture] CRTC[%u] fb=%u %ux%u pitch=%u bpp=%u handle=%u\n",
                i, fb_id, fb_w, fb_h, fb_pitch, fb_bpp, fb_handle);
        break;
    }
    free(crtc_ids);

    if (fb_id == 0 || fb_handle == 0) {
        fprintf(stderr, "No active framebuffer found\n");
        close(fd); return 1;
    }

    /* 通过 MAP_DUMB 获取 mmap offset */
    struct drm_mode_map_dumb map_dumb = {0};
    map_dumb.handle = fb_handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map_dumb)) {
        /* MAP_DUMB 失败，尝试 PRIME export */
        fprintf(stderr, "MAP_DUMB failed, trying PRIME\n");
        struct drm_prime_handle prime = {0};
        prime.handle = fb_handle;
        prime.flags = 0;
        if (ioctl(fd, DRM_IOCTL_PRIME_HANDLE_TO_FD, &prime)) {
            perror("PRIME"); close(fd); return 1;
        }
        size_t sz = (size_t)fb_h * fb_pitch;
        uint8_t *buf = mmap(NULL, sz, PROT_READ, MAP_SHARED, prime.fd, 0);
        if (buf == MAP_FAILED) { perror("mmap prime"); close(prime.fd); close(fd); return 1; }
        write_ppm(out, fb_w, fb_h, buf, fb_bpp);
        fprintf(stderr, "[drm_capture] PRIME OK: %dx%d -> %s\n", fb_w, fb_h, out);
        munmap(buf, sz); close(prime.fd); close(fd); return 0;
    }

    /* 用 MAP_DUMB offset mmap */
    size_t sz = (size_t)fb_h * fb_pitch;
    uint8_t *buf = mmap(NULL, sz, PROT_READ, MAP_SHARED, fd, map_dumb.offset);
    if (buf == MAP_FAILED) { perror("mmap dumb"); close(fd); return 1; }

    write_ppm(out, fb_w, fb_h, buf, fb_bpp);
    fprintf(stderr, "[drm_capture] MAP_DUMB OK: %dx%d -> %s\n", fb_w, fb_h, out);
    munmap(buf, sz);
    close(fd);
    return 0;
}
