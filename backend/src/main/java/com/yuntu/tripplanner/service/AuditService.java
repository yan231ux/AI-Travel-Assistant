package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路审计服务（阶段四任务 10）：集中写审计日志 + 分页查询。
 *
 * <p>{@link #record} 全程 fail-soft：审计插入失败只告警不影响主流程；
 * detail 统一 JSON 序列化并截断 ≤1000 字符。查询面给管理后台
 * （FeedOpsController GET /admin/audit-logs），按操作者/分类/时间窗过滤。
 */
@Slf4j
@Service
public class AuditService {

    private static final int DETAIL_MAX = 1000;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 写一条审计记录。
     *
     * @param actorId    操作者（可为 null：系统动作/登录失败等匿名场景）
     * @param category   分类常量（AuditLog.CAT_*）
     * @param action     动作标识（如 post_approved）
     * @param targetType 对象类型（post/comment/report/trip/experiment/user）
     * @param targetId   对象 ID（帖子/举报/行程 ID 或实验名等）
     * @param detail     附加上下文（可为 null）
     */
    public void record(String actorId, String category, String action,
                       String targetType, String targetId, Map<String, Object> detail) {
        try {
            AuditLog row = new AuditLog();
            row.setActorId(blankToNull(actorId));
            row.setCategory(category);
            row.setAction(action);
            row.setTargetType(blankToNull(targetType));
            row.setTargetId(blankToNull(targetId));
            row.setDetail(detail == null || detail.isEmpty() ? null : toJson(detail));
            auditLogRepository.insert(row);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响主流程）: action={} - {}", action, e.getMessage());
        }
    }

    /** 分页查询（管理后台）：按操作者/分类/时间窗过滤，新记录在前 */
    public AuditPage page(String actorId, String category, int days, int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getId);
        if (actorId != null && !actorId.isBlank()) {
            w.eq(AuditLog::getActorId, actorId);
        }
        if (category != null && !category.isBlank()) {
            w.eq(AuditLog::getCategory, category);
        }
        if (days > 0) {
            w.ge(AuditLog::getCreatedAt, LocalDateTime.now().minusDays(Math.min(days, 90)));
        }
        long total = auditLogRepository.selectCount(w);
        List<AuditLog> rows = auditLogRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        return new AuditPage(rows, total, pageNo, size);
    }

    /** 审计分页载体 */
    public record AuditPage(List<AuditLog> items, long total, int page, int pageSize) {
    }

    private String toJson(Map<String, Object> detail) {
        try {
            String json = objectMapper.writeValueAsString(detail);
            return json.length() <= DETAIL_MAX ? json : json.substring(0, DETAIL_MAX);
        } catch (Exception e) {
            // 序列化失败（理论上不会）：降级为平铺文本，仍尽量留痕
            String plain = String.valueOf(detail);
            return plain.length() <= DETAIL_MAX ? plain : plain.substring(0, DETAIL_MAX);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 便捷：构造 detail map（少写类型声明） */
    public static Map<String, Object> detailOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
