package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.CityTopic;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CityTopicService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 城市专题控制器（阶段四任务 1：/city/:name 专题页数据源）。
 *
 * <p>GET /city-topic?city=xxx 一次返回该城的景点聚合 + 攻略聚合 + 规模统计，
 * 供专题页首屏使用；城市不存在/数据为空时返回空列表而非报错（页面可诚实展示空态）。
 * userId 取自登录态（无登录态 → 景点热门降级，与推荐流语义一致）。
 */
@Slf4j
@RestController
@RequestMapping("/city-topic")
public class CityTopicController {

    private final CityTopicService cityTopicService;

    public CityTopicController(CityTopicService cityTopicService) {
        this.cityTopicService = cityTopicService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> topic(
            @RequestParam(required = false) String city) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            CityTopic topic = cityTopicService.topic(UserContext.getUserId(), city);
            body.put("success", true);
            body.put("data", topic);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("城市专题聚合失败: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "城市专题加载失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
