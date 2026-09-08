package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.service.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片上传控制器（阶段四⑨图片治理：本地上传到后端）。
 *
 * <p>路径有意避开 /uploads/**（该前缀被静态资源占用且已从 JWT 拦截器排除，
 * 图片 GET 无鉴权头）；上传本身要求登录（拦截器默认规则生效），
 * 校验/落盘/扩展名安全全部在 {@link UploadService}，非法请求由全局异常映射 400。
 */
@Slf4j
@RestController
@RequestMapping("/file")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** 上传图片（帖子封面等），返回可访问的相对 URL */
    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        String url = uploadService.saveImage(file);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
