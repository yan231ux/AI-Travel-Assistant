package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.service.SpotTrendingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页数据接口（设计方案 §6.4）。
 *
 * <p>与 {@code /admin/analytics/*} 的分工：那边是管理员看板（全量、带降级标记），
 * 这边是普通用户首页消费（已过滤 + 已打分 + 已做小样本保护）。
 * 路径未列入免登录白名单，因此需要登录态。
 */
@RestController
@RequestMapping("/home")
public class HomeTrendingController {

    private final SpotTrendingService spotTrendingService;

    public HomeTrendingController(SpotTrendingService spotTrendingService) {
        this.spotTrendingService = spotTrendingService;
    }

    /**
     * 「大家最近在规划」热门景点。
     *
     * <p>这是<b>社会热度</b>而非个性化推荐（§6.1）：不能把它当成"适合你"来展示，
     * 前端必须与个性化模块分开标注。返回体带 degraded/errors，查询失败时显式降级。
     *
     * @param city  可选城市过滤；缺省时跨城市比较（各城市内部归一化后可比）
     * @param days  统计窗口，默认 7 天（内部限制 1~30）
     * @param limit 返回条数，默认 8（内部限制 1~20）
     */
    @GetMapping("/trending-spots")
    public ResponseEntity<Map<String, Object>> trendingSpots(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok(spotTrendingService.trendingSpots(days, city, limit));
    }
}
