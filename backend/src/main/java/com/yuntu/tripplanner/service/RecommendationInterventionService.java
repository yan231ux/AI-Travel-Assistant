package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.SpotVisibility;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.RecommendationIntervention;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.RecommendationInterventionRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐人工干预服务（设计方案 §6.3 /admin/recommendations/interventions）。
 *
 * <p>低风险运营动作登记：PIN(置顶)/DEMOTE(降权)/BLACKLIST(推荐黑名单) 作用于景点，
 * FEATURED(城市精选) 作用于城市。不提供"手填最终分"——干预只在推荐流排序层消费
 * （黑名单剔除 / 置顶优先 / 降权沉底），不写任何 score/quality 字段，与算法分数严格分离。
 * 每个保存/删除都审计（recommendation_intervened / recommendation_intervention_removed）。
 *
 * <p>说明：GUIDE_PRIORITY（指定攻略优先）本期不做——攻略卡内容在 RAG 检索层生成，
 * 干预单点指定需侵入检索链路且不可即时演示，属于"数据写入层干预"，与本期
 * "只做读路径排序层干预"的原则冲突，留待攻略运营深化时再评估（取舍记档）。
 */
@Slf4j
@Service
public class RecommendationInterventionService {

    /** 动作 × 对象矩阵：SPOT 可用 PIN/DEMOTE/BLACKLIST；CITY 仅 FEATURED */
    private static final Map<String, Set<String>> ACTION_MATRIX = Map.of(
            RecommendationIntervention.TARGET_SPOT, Set.of(
                    RecommendationIntervention.ACTION_PIN,
                    RecommendationIntervention.ACTION_DEMOTE,
                    RecommendationIntervention.ACTION_BLACKLIST),
            RecommendationIntervention.TARGET_CITY, Set.of(
                    RecommendationIntervention.ACTION_FEATURED));

    private final RecommendationInterventionRepository interventionRepository;
    private final SpotRepository spotRepository;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    public RecommendationInterventionService(RecommendationInterventionRepository interventionRepository,
                                             SpotRepository spotRepository,
                                             CommunityUserService communityUserService,
                                             AuditService auditService) {
        this.interventionRepository = interventionRepository;
        this.spotRepository = spotRepository;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /* ================= 管理端 ================= */

    /** 干预列表（scope=ALL/ACTIVE/SCHEDULED/EXPIRED；action/targetType 可选过滤；分页） */
    public Map<String, Object> page(String adminId, String action, String targetType, String scope,
                                    int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.RECOMMEND_OPS);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<RecommendationIntervention> w =
                new LambdaQueryWrapper<RecommendationIntervention>()
                        .orderByDesc(RecommendationIntervention::getCreatedAt);
        if (action != null && !action.isBlank()) {
            w.eq(RecommendationIntervention::getAction, action.trim().toUpperCase());
        }
        if (targetType != null && !targetType.isBlank()) {
            w.eq(RecommendationIntervention::getTargetType, targetType.trim().toUpperCase());
        }
        LocalDateTime now = LocalDateTime.now();
        String sc = scope == null ? "ALL" : scope.trim().toUpperCase();
        if ("ACTIVE".equals(sc)) {
            w.and(x -> x.and(y -> y.isNull(RecommendationIntervention::getEffectiveFrom)
                            .or().le(RecommendationIntervention::getEffectiveFrom, now))
                    .and(y -> y.isNull(RecommendationIntervention::getEffectiveUntil)
                            .or().gt(RecommendationIntervention::getEffectiveUntil, now)));
        } else if ("SCHEDULED".equals(sc)) {
            w.gt(RecommendationIntervention::getEffectiveFrom, now);
        } else if ("EXPIRED".equals(sc)) {
            w.isNotNull(RecommendationIntervention::getEffectiveUntil)
                    .le(RecommendationIntervention::getEffectiveUntil, now);
        }
        long total = interventionRepository.selectCount(w);
        List<RecommendationIntervention> rows = interventionRepository.selectList(
                w.last("LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        List<Map<String, Object>> items = withSpotInfo(rows, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", pageNo);
        body.put("pageSize", size);
        return body;
    }

    /** 新建/覆盖保存（同对象同动作 = 覆盖窗口与原因；审计区分 created/updated） */
    @Transactional
    public Map<String, Object> save(String adminId, Map<String, Object> req) {
        communityUserService.requirePermission(adminId, AdminPermission.RECOMMEND_OPS);
        String targetType = str(req.get("target_type")).toUpperCase();
        String targetId = str(req.get("target_id"));
        String action = str(req.get("action")).toUpperCase();
        String reason = str(req.get("reason"));
        if (targetType == null || !ACTION_MATRIX.containsKey(targetType)) {
            throw new IllegalArgumentException("干预对象类型需为 SPOT 或 CITY");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("干预对象不能为空（SPOT=spot_id / CITY=城市名）");
        }
        if (action == null || !ACTION_MATRIX.get(targetType).contains(action)) {
            throw new IllegalArgumentException(targetType + " 支持的干预动作："
                    + String.join("/", ACTION_MATRIX.get(targetType)));
        }
        if (reason == null || reason.length() > 255) {
            throw new IllegalArgumentException("请填写 1~255 字的运营原因（展示与审计）");
        }
        LocalDateTime from = time(req.get("effective_from"));
        LocalDateTime until = time(req.get("effective_until"));
        if (from != null && until != null && !from.isBefore(until)) {
            throw new IllegalArgumentException("生效时间必须早于失效时间");
        }
        // SPOT 对象预检：不存在/已治理冻结时给出明确提示（仍允许登记，但说明当前不会外露）
        if (RecommendationIntervention.TARGET_SPOT.equals(targetType)) {
            precheckSpot(targetId, action);
        }

        RecommendationIntervention existing = interventionRepository.selectOne(
                new LambdaQueryWrapper<RecommendationIntervention>()
                        .eq(RecommendationIntervention::getTargetType, targetType)
                        .eq(RecommendationIntervention::getTargetId, targetId)
                        .eq(RecommendationIntervention::getAction, action)
                        .last("LIMIT 1"));
        boolean updated;
        if (existing == null) {
            RecommendationIntervention row = new RecommendationIntervention();
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setAction(action);
            row.setReason(reason);
            row.setEffectiveFrom(from);
            row.setEffectiveUntil(until);
            row.setCreatedBy(adminId);
            interventionRepository.insert(row);
            updated = false;
        } else {
            interventionRepository.update(null, new LambdaUpdateWrapper<RecommendationIntervention>()
                    .eq(RecommendationIntervention::getId, existing.getId())
                    .set(RecommendationIntervention::getReason, reason)
                    .set(RecommendationIntervention::getEffectiveFrom, from)
                    .set(RecommendationIntervention::getEffectiveUntil, until)
                    .set(RecommendationIntervention::getCreatedBy, adminId));
            updated = true;
        }
        String targetDesc = targetType + ":" + targetId;
        auditService.record(adminId, AuditLog.CAT_ADMIN, "recommendation_intervened", "recommendation",
                targetDesc,
                AuditService.detailOf("action", action, "target_type", targetType,
                        "target_id", targetId, "reason", reason,
                        "effective_from", from == null ? null : from.toString(),
                        "effective_until", until == null ? null : until.toString(),
                        "mode", updated ? "updated" : "created"));
        log.info("推荐人工干预: {} {} {} by={} reason={} mode={}",
                targetType, targetId, action, adminId, reason, updated ? "updated" : "created");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", updated ? "updated" : "created");
        r.put("target_type", targetType);
        r.put("target_id", targetId);
        r.put("action", action);
        return r;
    }

    /** 删除干预（审计留痕，动作即时不再生效） */
    @Transactional
    public void remove(String adminId, long id) {
        communityUserService.requirePermission(adminId, AdminPermission.RECOMMEND_OPS);
        RecommendationIntervention row = interventionRepository.selectById(id);
        if (row == null) {
            return;
        }
        interventionRepository.deleteById(id);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "recommendation_intervention_removed",
                "recommendation", row.getTargetType() + ":" + row.getTargetId(),
                AuditService.detailOf("action", row.getAction(), "reason", row.getReason()));
        log.info("推荐人工干预移除: id={} {} {} by={}", id, row.getTargetType(), row.getTargetId(), adminId);
    }

    /* ================= 推荐流消费（只读，feed 每请求一次） ================= */

    /** 当前窗口内生效的全部干预（feed 排序层消费；表很小，一次全量查询） */
    public List<RecommendationIntervention> activeNow() {
        LocalDateTime now = LocalDateTime.now();
        return interventionRepository.selectList(new LambdaQueryWrapper<RecommendationIntervention>()
                .and(w -> w.and(x -> x.isNull(RecommendationIntervention::getEffectiveFrom)
                                .or().le(RecommendationIntervention::getEffectiveFrom, now))
                        .and(x -> x.isNull(RecommendationIntervention::getEffectiveUntil)
                                .or().gt(RecommendationIntervention::getEffectiveUntil, now))));
    }

    /* ================= 载体 ================= */

    private void precheckSpot(String spotId, String action) {
        Spot spot = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                .eq(Spot::getSpotId, spotId).last("LIMIT 1"));
        if (spot == null) {
            throw new IllegalArgumentException("景点不存在（spot_id=" + spotId
                    + "），请先在景点数据治理中确认");
        }
        if (!SpotVisibility.isActive(spot)) {
            log.warn("推荐干预登记的目标当前不可外露（下线/合并/异常标记），动作将不产生实际效果: spot={} action={}",
                    spotId, action);
        }
    }

    private List<Map<String, Object>> withSpotInfo(List<RecommendationIntervention> rows, LocalDateTime now) {
        // 批量取 SPOT 名称/城市，避免 N+1
        Set<String> spotIds = rows.stream()
                .filter(r -> RecommendationIntervention.TARGET_SPOT.equals(r.getTargetType()))
                .map(RecommendationIntervention::getTargetId)
                .collect(Collectors.toSet());
        Map<String, Spot> spots = new LinkedHashMap<>();
        if (!spotIds.isEmpty()) {
            for (Spot s : spotRepository.selectList(new LambdaQueryWrapper<Spot>()
                    .in(Spot::getSpotId, spotIds))) {
                spots.put(s.getSpotId(), s);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (RecommendationIntervention r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.getId()));
            m.put("target_type", r.getTargetType());
            m.put("target_id", r.getTargetId());
            m.put("action", r.getAction());
            m.put("reason", r.getReason());
            m.put("effective_from", r.getEffectiveFrom() == null ? null
                    : r.getEffectiveFrom().toString().replace("T", " "));
            m.put("effective_until", r.getEffectiveUntil() == null ? null
                    : r.getEffectiveUntil().toString().replace("T", " "));
            m.put("created_by", r.getCreatedBy());
            m.put("created_at", r.getCreatedAt() == null ? null
                    : r.getCreatedAt().toString().replace("T", " "));
            m.put("status", statusOf(r, now));
            if (RecommendationIntervention.TARGET_SPOT.equals(r.getTargetType())) {
                Spot s = spots.get(r.getTargetId());
                m.put("spot_name", s == null ? null : s.getName());
                m.put("spot_city", s == null ? null : s.getCity());
            }
            items.add(m);
        }
        return items;
    }

    /** 状态：SCHEDULED(未到生效) / EXPIRED(已过期) / ACTIVE */
    private static String statusOf(RecommendationIntervention r, LocalDateTime now) {
        if (r.getEffectiveFrom() != null && r.getEffectiveFrom().isAfter(now)) {
            return "SCHEDULED";
        }
        if (r.getEffectiveUntil() != null && !r.getEffectiveUntil().isAfter(now)) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static LocalDateTime time(Object o) {
        String s = str(o);
        if (s == null) {
            return null;
        }
        String t = s.replace(" ", "T");
        // 兼容 "2026-09-10 08:00" / ISO；纯日期 "2026-09-10" 视为当天 00:00
        try {
            return LocalDateTime.parse(t);
        } catch (Exception ignore) {
            return java.time.LocalDate.parse(t).atStartOfDay();
        }
    }
}
