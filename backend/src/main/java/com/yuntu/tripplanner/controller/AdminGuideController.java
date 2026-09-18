package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CityGuideService;
import com.yuntu.tripplanner.service.CityGuideService.GuideEdit;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.GuideIndexingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 攻略运营控制器（/admin/guides：内容运营中心 §4.2，攻略骨架 CRUD + 草稿/提交/发布/下线
 * + Markdown 幂等导入 + RAG 索引任务触发/重试）。全部要求 GUIDE_MANAGE 权限。
 *
 * <p>校验位置说明：绝大多数接口经 {@link CityGuideService} 时由其实例内部校验；唯独
 * {@code /{id}/reindex} 直连底层 {@link GuideIndexingService}（该服务不感知登录用户，
 * 也无法注入 CommunityUserService），所以它的校验必须写在<b>本控制器</b>里。
 */
@Slf4j
@RestController
@RequestMapping("/admin/guides")
public class AdminGuideController {

    private final CityGuideService guideService;
    private final GuideIndexingService indexingService;
    private final CommunityUserService communityUserService;

    public AdminGuideController(CityGuideService guideService,
                                GuideIndexingService indexingService,
                                CommunityUserService communityUserService) {
        this.guideService = guideService;
        this.indexingService = indexingService;
        this.communityUserService = communityUserService;
    }

    /** 攻略列表（城市/状态/标题关键词过滤） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", guideService.page(UserContext.getUserId(), city, status, keyword, page, pageSize));
        return ResponseEntity.ok(body);
    }

    /** 攻略详情（正文 + 版本历史 + 景点/标签 + 索引任务） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", guideService.detail(UserContext.getUserId(), id));
        return ResponseEntity.ok(body);
    }

    /** 新建攻略草稿 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, String> req) {
        var guide = guideService.create(UserContext.getUserId(), editOf(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "草稿已创建（v" + guide.getVersion() + "）");
        body.put("id", guide.getId());
        return ResponseEntity.ok(body);
    }

    /** 保存编辑：内容无变化跳过（返回 unchanged=true），有变化 append 新版本 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, String> req) {
        Integer version = guideService.update(UserContext.getUserId(), id, editOf(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("unchanged", version == null);
        body.put("version", version == null ? 0 : version);
        body.put("message", version == null ? "内容无变化，未产生新版本" : "已保存为 v" + version);
        return ResponseEntity.ok(body);
    }

    /** 提交审核：DRAFT/REJECTED → PENDING_REVIEW */
    @PostMapping("/{id}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable Long id) {
        guideService.submit(UserContext.getUserId(), id);
        return ok("已提交审核");
    }

    /** 审核通过发布：置 PUBLISHED 并登记 RAG 索引任务、异步执行；线上版本在索引成功后切换（两段式） */
    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable Long id) {
        guideService.publish(UserContext.getUserId(), id);
        indexingService.indexPendingAsync(id);
        return ok("已发布，RAG 索引任务执行中（索引成功即切换为线上版本，可稍后在详情页查看索引状态）");
    }

    /** 审核拒绝（必须带原因） */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        guideService.reject(UserContext.getUserId(), id, reason);
        return ok("已拒绝");
    }

    /** 下线（PUBLISHED → HIDDEN，保留版本可恢复） */
    @PostMapping("/{id}/hide")
    public ResponseEntity<Map<String, Object>> hide(@PathVariable Long id) {
        guideService.hide(UserContext.getUserId(), id);
        return ok("已下线");
    }

    /** 恢复公开（HIDDEN → PUBLISHED） */
    @PostMapping("/{id}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable Long id) {
        guideService.restore(UserContext.getUserId(), id);
        return ok("已恢复公开");
    }

    /** 归档（PUBLISHED/HIDDEN → ARCHIVED：停止公开消费并从 RAG 检索源移除） */
    @PostMapping("/{id}/archive")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable Long id) {
        guideService.archive(UserContext.getUserId(), id);
        return ok("已归档（可从「归档」过滤查看；取消归档回草稿）");
    }

    /** 取消归档（ARCHIVED → DRAFT，保留版本历史继续维护） */
    @PostMapping("/{id}/unarchive")
    public ResponseEntity<Map<String, Object>> unarchive(@PathVariable Long id) {
        guideService.unarchive(UserContext.getUserId(), id);
        return ok("已取消归档，转为草稿（提交审核并发布后重新进入 RAG）");
    }

    /** 复制为新版本（fork 一条全新草稿，不触碰原攻略） */
    @PostMapping("/{id}/copy")
    public ResponseEntity<Map<String, Object>> copy(@PathVariable Long id) {
        Long newId = guideService.copy(UserContext.getUserId(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已复制为新版本（草稿 #" + newId + "），可独立编辑与发布");
        body.put("id", newId);
        return ResponseEntity.ok(body);
    }

    /** 回滚上一已发布版本（撤销待审编辑 / 错误发布，历史版本 append-only 保留） */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable Long id) {
        int revisionNo = guideService.rollback(UserContext.getUserId(), id);
        return ok("已回滚到上一已发布版本 v" + revisionNo + "（RAG 索引将同步重建）");
    }

    /** 静态 Markdown 幂等导入（source_file + content_hash 判重；文件变更入待审不覆盖线上） */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importGuides() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Markdown 导入完成，RAG 索引任务已入队（未发布草稿不入队）");
        body.put("data", guideService.importFromClasspath(UserContext.getUserId()));
        indexingService.indexAllPendingAsync();
        return ResponseEntity.ok(body);
    }

    /** 手动重建/重试 RAG 索引（索引失败重试 / 更换模型后重建）：同步执行并返回任务结果 */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable Long id) {
        // 本接口直接调底层索引服务（不像其余接口那样经 CityGuideService 兜底校验），
        // 因此权限必须在这里显式校验，否则任何登录用户都能触发全量重建索引。
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.GUIDE_MANAGE);
        Map<String, Object> taskResult = indexingService.reindex(id, UserContext.getUserId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "READY".equals(taskResult.get("status"))
                ? "索引重建成功，新版本已生效" : "索引失败（旧版本继续服务），详见错误信息");
        body.put("data", taskResult);
        return ResponseEntity.ok(body);
    }

    /* ================= 载体 ================= */

    private static GuideEdit editOf(Map<String, String> req) {
        if (req == null) {
            req = Map.of();
        }
        return new GuideEdit(req.get("city"), req.get("title"), req.get("summary"),
                req.get("coverImage"), req.get("sourceType"), req.get("sourceName"),
                req.get("content"), req.get("changeSummary"));
    }

    private static ResponseEntity<Map<String, Object>> ok(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }
}
