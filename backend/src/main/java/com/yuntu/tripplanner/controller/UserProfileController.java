package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.PreferenceAdjustment;
import com.yuntu.tripplanner.model.ProfileSummary;
import com.yuntu.tripplanner.model.QuestionnaireRequest;
import com.yuntu.tripplanner.model.SpotFavorite;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.model.UserProfile;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.RecommendationService;
import com.yuntu.tripplanner.service.SpotService;
import com.yuntu.tripplanner.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像控制器（个性化阶段一/二/四的对外门面）。
 *
 * <p>GET /user/profile 返回结构化画像（主档 + 带权重/置信度/来源的偏好明细），
 * 前端「我了解你」卡片与画像页据此展示；无画像行时服务端会基于历史行程惰性构建一次。
 * PUT /user/profile/questionnaire 接收首次偏好问卷/画像编辑，显式偏好以最高可信度入库。
 * POST /user/behavior 接收结果页的行为反馈（收藏/不感兴趣/评分），留痕并增量更新画像权重；
 * 真实改变权重会触发 user_profile.profileVersion+1，使结果缓存 key 失效（闭环见阶段三）。
 * GET /user/profile/stats 返回个性化效果指标（推荐命中率/负反馈率/平均满意度），
 * 数据源为阶段四的 recommendation_log 推荐日志 + user_behavior 行为留痕。
 * 四个接口均在 JWT 保护区内（/user/** 需登录），userId 取自登录态而非请求体。
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final RecommendationService recommendationService;
    private final SpotService spotService;

    public UserProfileController(UserProfileService userProfileService,
                                 RecommendationService recommendationService,
                                 SpotService spotService) {
        this.userProfileService = userProfileService;
        this.recommendationService = recommendationService;
        this.spotService = spotService;
    }

    /**
     * 查询我的画像：{profile, preferences[]}。首次访问自动从历史行程推断建档（老用户平滑升级）。
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile() {
        try {
            String userId = UserContext.getUserId();
            UserProfile profile = userProfileService.loadOrInitProfile(userId);
            List<UserPreference> preferences = userProfileService.listPreferences(userId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("profile", profile);
            body.put("preferences", preferences);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("查询用户画像失败", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "画像查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 提交/更新偏好问卷（可重复提交，后填覆盖先填；未填写的域保持现状）。
     * 返回更新后的画像视图。
     */
    @PutMapping("/profile/questionnaire")
    public ResponseEntity<Map<String, Object>> saveQuestionnaire(@RequestBody(required = false) QuestionnaireRequest request) {
        try {
            String userId = UserContext.getUserId();
            UserProfile profile = userProfileService.applyQuestionnaire(userId, request);
            List<UserPreference> preferences = userProfileService.listPreferences(userId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("profile", profile);
            body.put("preferences", preferences);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("保存偏好问卷失败", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "问卷保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 个性化效果统计（个性化阶段四）：偏好命中率 / 负反馈率 / 平均满意度 / 推荐日志条数。
     * 数据来源 recommendation_log + user_behavior，作为阶段四对照实验的量化指标。
     */
    @GetMapping("/profile/stats")
    public ResponseEntity<Map<String, Object>> getProfileStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            body.put("success", true);
            body.put("stats", recommendationService.collectStats(userId));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("查询个性化效果统计失败", e);
            body.put("success", false);
            body.put("error", "统计查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 上报行为反馈（个性化阶段二）：景点收藏/不感兴趣/替换、行程整体评分等。
     * 行为先落库 user_behavior；命中偏好标签的行为（SPOT→旅行风格、RESTAURANT→口味）
     * 同步增量更新画像权重（SAVE +0.15 / DISLIKE -0.30 / REPLACE -0.20，见 PERSONALIZATION_PLAN 5.3），
     * 返回 adjustments 供前端提示"本次反馈改变了什么"。
     */
    @PostMapping("/behavior")
    public ResponseEntity<Map<String, Object>> reportBehavior(@RequestBody(required = false) BehaviorRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            List<PreferenceAdjustment> adjustments = userProfileService.recordBehavior(userId, request);
            body.put("success", true);
            body.put("message", "行为已记录");
            body.put("adjustments", adjustments);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("行为上报参数错误: {}", e.getMessage());
            body.put("success", false);
            body.put("error", "行为上报参数错误");
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (Exception e) {
            log.error("记录用户行为失败", e);
            body.put("success", false);
            body.put("error", "行为上报失败");
            body.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 用户旅行摘要（Q5 修复：实时聚合 trip_record，取代过时快照字段）。
     * 首页画像摘要卡与 ProfileView「来自你的历史行程」卡统一用它 —— 保证显示与
     * History 列表一致（曾出现"显示 5 次/4 城、实际 11 次/8 城"的漂移）。
     */
    @GetMapping("/profile/summary")
    public ResponseEntity<Map<String, Object>> getProfileSummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            ProfileSummary summary = userProfileService.buildProfileSummary(userId);
            body.put("success", true);
            body.put("data", summary);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("查询旅行摘要失败", e);
            body.put("success", false);
            body.put("error", "旅行摘要查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * 我的景点收藏列表（产品化阶段一 /favorites 数据源；收藏快照字段直出）。
     */
    @GetMapping("/spot-favorites")
    public ResponseEntity<Map<String, Object>> listFavorites(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "12") int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String userId = UserContext.getUserId();
            List<SpotFavorite> items = spotService.listFavorites(userId, page, pageSize);
            long total = spotService.countFavorites(userId);
            body.put("success", true);
            body.put("items", items);
            body.put("total", total);
            body.put("page", Math.max(1, page));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("查询我的收藏失败", e);
            body.put("success", false);
            body.put("error", "收藏查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
