package com.yuntu.tripplanner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 图片上传服务（阶段四⑨图片治理：本地上传到后端，无对象存储的可行解）。
 *
 * <p>安全与合规要点（答辩口径）：
 * 1) 扩展名不信任客户端文件名，一律按 Content-Type 白名单映射（jpeg/png/webp/gif）；
 * 2) 再校验文件头魔数（FFD8FF / PNG 签名 / GIF8 / RIFF..WEBP），防止伪装类型上传非图片内容；
 * 3) 大小上限 5MB（超出直接拒绝，配合 spring.servlet.multipart 请求级上限双保险）；
 * 4) 文件名用 UUID 随机生成，不落任何用户可控字符（防路径穿越/覆盖）；
 * 5) 存储目录由 app.upload-dir 配置（默认 ./uploads，即后端工作目录），
 *    经 WebConfig 静态映射以 /uploads/** 对外提供，该前缀已从 JWT 拦截器排除（图片 GET 无鉴权头）。
 *
 * <p>返回相对 URL（如 /uploads/xxx.jpg），前端统一经 resolveImageUrl 拼 API_BASE_URL 展示，
 * 避免把 localhost 域名写死进数据库。
 */
@Slf4j
@Service
public class UploadService {

    /** 单张图片大小上限：5MB */
    public static final long MAX_BYTES = 5 * 1024 * 1024;

    /** Content-Type → 存储扩展名白名单 */
    private static final Map<String, String> TYPE_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final Path uploadDir;

    public UploadService(@Value("${app.upload-dir:./uploads}") String dir) {
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** 校验并落盘，返回可访问的相对 URL（/uploads/&lt;uuid&gt;.&lt;ext&gt;） */
    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 5MB");
        }
        String contentType = file.getContentType();
        String ext = TYPE_EXT.get(contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
        if (ext == null) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/WebP/GIF 格式图片");
        }

        // 魔数二次校验：Content-Type 可被伪造，真实文件头不可
        byte[] head = readHead(file);
        if (!matchesMagic(head, ext)) {
            throw new IllegalArgumentException("文件内容与图片格式不符，请重新选择图片");
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.createDirectories(uploadDir);
            file.transferTo(uploadDir.resolve(filename));
        } catch (IOException e) {
            log.error("图片落盘失败: {}", e.getMessage());
            throw new IllegalStateException("图片保存失败，请稍后重试");
        }
        log.info("图片上传成功: {} ({} bytes, {})", filename, file.getSize(), contentType);
        return "/uploads/" + filename;
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(16);
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage());
            throw new IllegalStateException("图片读取失败，请重试");
        }
    }

    private boolean matchesMagic(byte[] head, String ext) {
        return switch (ext) {
            case "jpg" -> head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png" -> head.length >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E
                    && head[3] == 0x47 && head[4] == 0x0D && head[5] == 0x0A
                    && head[6] == 0x1A && head[7] == 0x0A;
            case "gif" -> head.length >= 4
                    && head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x38;
            case "webp" -> head.length >= 12
                    && head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                    && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50;
            default -> false;
        };
    }
}
