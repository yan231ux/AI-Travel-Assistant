package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.SpotNotFoundException;
import com.yuntu.tripplanner.model.SpotDetail;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.SpotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 景点详情与收藏控制器（产品化阶段一：GET /spots/{spotId} + 收藏/不感兴趣）。
 *
 * <p>职责收敛（Review P1-1）：Controller 只负责参数/HTTP 映射；「收藏行写入 + SAVE 行为
 * 留痕 + 画像增量」已全部收进 {@link SpotService#favorite} 同一事务，这里不再直接调画像。
 * 景点不存在 → Service 抛 {@link SpotNotFoundException} → 统一 404（Review P1-2），
 * 不存在的景点不会返回"收藏成功"。
 */
@Slf4j
@RestController
@RequestMapping("/spots")
public class SpotController {

    private final SpotService spotService;

    public SpotController(SpotService spotService) {
        this.spotService = spotService;
    }

    /** 景点详情（含可信度/是否去过/收藏状态/相关推荐） */
    @GetMapping("/{spotId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String spotId) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            SpotDetail detail = spotService.detail(userId, spotId);
            if (detail == null) {
                body.put("success", false);
                body.put("error", "景点不存在");
                return ResponseEntity.status(404).body(body);
            }
            body.put("success", true);
            body.put("data", detail);
            return ResponseEntity.ok(body);
        } catch (SpotNotFoundException e) {
            body.put("success", false);
            body.put("error", "景点不存在");
            return ResponseEntity.status(404).body(body);
        } catch (Exception e) {
            log.error("景点详情失败: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "景点详情加载失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 收藏景点（幂等 + 单事务，Review P1-1/2/3）。
     * 首次收藏在同一事务内完成收藏行 + SAVE 行为 + 画像升权；重复收藏/并发命中唯一键
     * 均返回 existed=true 不重复计数；景点不存在返回 404。
     */
    @PostMapping("/{spotId}/favorite")
    public ResponseEntity<Map<String, Object>> favorite(@PathVariable String spotId) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            SpotService.FavoriteResult result = spotService.favorite(userId, spotId);
            body.put("success", true);
            body.put("existed", !result.isNewly());
            body.put("message", result.isNewly() ? "已收藏" : "已在收藏中");
            body.put("adjustments", result.getAdjustments());
            return ResponseEntity.ok(body);
        } catch (SpotNotFoundException e) {
            body.put("success", false);
            body.put("error", "景点不存在");
            return ResponseEntity.status(404).body(body);
        } catch (Exception e) {
            log.error("收藏失败: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "收藏失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /** 取消收藏（幂等：未收藏也不报错；不反噬画像权重）。景点不存在 → 404 */
    @DeleteMapping("/{spotId}/favorite")
    public ResponseEntity<Map<String, Object>> unfavorite(@PathVariable String spotId) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            spotService.unfavorite(userId, spotId);
            body.put("success", true);
            body.put("message", "已取消收藏");
            return ResponseEntity.ok(body);
        } catch (SpotNotFoundException e) {
            body.put("success", false);
            body.put("error", "景点不存在");
            return ResponseEntity.status(404).body(body);
        } catch (Exception e) {
            log.error("取消收藏失败: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "取消收藏失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
