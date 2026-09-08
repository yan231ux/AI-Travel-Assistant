package com.yuntu.tripplanner.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传服务单测（阶段四⑨图片治理）：白名单/魔数/大小/落盘。
 * 核心目标：Content-Type 伪装、超大文件、非图片一律 400，真实图片按 UUID 落盘并返回相对 URL。
 */
class UploadServiceTest {

    @TempDir
    Path tempDir;

    private UploadService newService() {
        return new UploadService(tempDir.toString());
    }

    private static byte[] pngBytes() {
        // PNG 签名 + 任意填充（仅测落盘与魔数，不解码）
        byte[] b = new byte[64];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private static byte[] jpgBytes() {
        byte[] b = new byte[32];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] webpBytes() {
        byte[] b = "RIFF1234WEBP-fill-fill-fill-fill".getBytes();
        return b;
    }

    @Test
    void savePng_returnsRelativeUrlAndWritesFile() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes());
        String url = newService().saveImage(file);

        assertTrue(url.matches("/uploads/[0-9a-f]{32}\\.png"), "URL 应为 /uploads/<uuid>.png，实得: " + url);
        Path saved = tempDir.resolve(url.substring("/uploads/".length()));
        assertTrue(Files.exists(saved), "文件应已落盘");
        byte[] onDisk = Files.readAllBytes(saved);
        assertEquals(pngBytes()[0], onDisk[0]);
    }

    @Test
    void saveJpg_andWebp_ok() {
        UploadService service = newService();
        assertEquals("/uploads/", service.saveImage(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpgBytes())).substring(0, 9));
        assertEquals("/uploads/", service.saveImage(new MockMultipartFile("file", "a.webp", "image/webp", webpBytes())).substring(0, 9));
    }

    @Test
    void rejectUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "a.html", "text/html", "<html></html>".getBytes());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> newService().saveImage(file));
        assertTrue(ex.getMessage().contains("仅支持"));
    }

    @Test
    void rejectSpoofedContentType_whenMagicMismatch() {
        // Content-Type 声明 png，实际是 jpeg 头 → 魔数校验必须拒绝
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", jpgBytes());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> newService().saveImage(file));
        assertTrue(ex.getMessage().contains("文件内容与图片格式不符"));
    }

    @Test
    void rejectOversize() {
        byte[] big = new byte[(int) UploadService.MAX_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> newService().saveImage(file));
        assertTrue(ex.getMessage().contains("5MB"));
    }

    @Test
    void rejectEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> newService().saveImage(file));
        assertTrue(ex.getMessage().contains("请选择"));
    }
}
