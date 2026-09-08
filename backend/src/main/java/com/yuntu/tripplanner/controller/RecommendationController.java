package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.RecommendationFeed;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.RecommendationFeedService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推荐景点流控制器（产品化阶段一：首页"为你推荐"与 /recommendations 发现页共用）。
 *
 * <p>GET /recommendations/spots 返回分页推荐卡片：登录且有 travel_style 画像 → 个性化排序
 * （复用统一评分器）；无画像/新用户 → 自动降级热门（不返回空列表）。
 * userId 取自登录态（JWT），无登录态视为匿名 → 热门降级（供首页首屏可用）。
 */
@Slf4j
@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private final RecommendationFeedService feedService;

    public RecommendationController(RecommendationFeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 推荐景点分页流。
     *
     * @param city     城市（如"上海"）；为空返回空列表
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 12，上限 50
     * @param sort     personalized（默认）/ popular / latest
     */
    @GetMapping("/spots")
    public ResponseEntity<Map<String, Object>> feed(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, name = "feed_trace_id") String feedTraceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId(); // 未登录时为 null → 热门降级
            RecommendationFeed feed = feedService.feed(userId, city, page, pageSize, sort, feedTraceId);
            body.put("success", true);
            body.put("data", feed);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("推荐景点流失败: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "推荐加载失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
