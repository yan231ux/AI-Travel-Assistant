package com.yuntu.tripplanner.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行健康检查（审查报告 P2-6 /product 化审查建议 §三.P2-6）。
 *
 * <p>GET /system/health（免登录）输出各依赖的真实状态，供启动/演示前快速确认环境：
 * 数据库连通、Redis 连通、上传目录可写、RAG 攻略向量条数、LLM/高德密钥是否已配置。
 * 只输出「是否配置/是否可用」的布尔与数量，绝不输出密钥等敏感值；fail-soft：
 * 任何单项异常都只标记状态，不抛 500（健康检查本身要稳定可用）。
 */
@Slf4j
@RestController
@RequestMapping("/system")
public class SystemHealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final String uploadDir;
    private final String llmApiKey;
    private final String amapApiKey;

    public SystemHealthController(JdbcTemplate jdbcTemplate,
                                  RedisConnectionFactory redisConnectionFactory,
                                  @Value("${app.upload-dir:./uploads}") String uploadDir,
                                  @Value("${llm.api-key:}") String llmApiKey,
                                  @Value("${amap.api-key:}") String amapApiKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.uploadDir = uploadDir;
        this.llmApiKey = llmApiKey;
        this.amapApiKey = amapApiKey;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> checks = new LinkedHashMap<>();

        boolean dbUp = dbUp();
        checks.put("db", dbUp ? "up" : "down");
        checks.put("redis", redisUp() ? "up" : "down");
        checks.put("uploadDir", uploadDirState());
        checks.put("ragGuideEmbeddings", ragGuideCount());
        checks.put("llmKeyConfigured", configured(llmApiKey));
        checks.put("amapKeyConfigured", configured(amapApiKey));

        body.put("success", true);
        body.put("service", "trip-planner");
        body.put("status", dbUp ? "ok" : "degraded"); // 数据库是最核心依赖：挂了整体标记 degraded
        body.put("checks", checks);
        body.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return ResponseEntity.ok(body);
    }

    private boolean dbUp() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (Exception e) {
            log.warn("健康检查：数据库不可达 - {}", e.getMessage());
            return false;
        }
    }

    private boolean redisUp() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(conn.ping());
        } catch (Exception e) {
            log.warn("健康检查：Redis 不可达 - {}", e.getMessage());
            return false;
        }
    }

    /** 上传目录状态：不存在/只读/可写（UploadService 首次上传时会自动建目录） */
    private Map<String, Object> uploadDirState() {
        Map<String, Object> state = new LinkedHashMap<>();
        Path dir = Paths.get(uploadDir == null || uploadDir.isBlank() ? "./uploads" : uploadDir);
        boolean exists = Files.isDirectory(dir);
        state.put("exists", exists);
        state.put("writable", exists && Files.isWritable(dir));
        return state;
    }

    /** RAG 攻略向量条数（来自 guide_embedding 表；查询失败返回 -1 表示未知，不影响检查本身） */
    private long ragGuideCount() {
        try {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM guide_embedding", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.debug("健康检查：RAG 向量计数查询失败 - {}", e.getMessage());
            return -1;
        }
    }

    private static boolean configured(String key) {
        return key != null && !key.isBlank();
    }
}
