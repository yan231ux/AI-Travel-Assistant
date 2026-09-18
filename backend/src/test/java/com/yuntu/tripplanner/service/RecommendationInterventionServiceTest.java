package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.RecommendationIntervention;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.RecommendationInterventionRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 推荐人工干预服务（设计方案 §6.3）单测：
 * requirePermission(RECOMMEND_OPS)、动作×对象矩阵校验、SPOT 存在性预检、原因必填、时间窗校验、
 * upsert 语义（同对象同动作覆盖）、审计留痕、删除与 activeNow。
 */
@ExtendWith(MockitoExtension.class)
class RecommendationInterventionServiceTest {

    @Mock
    private RecommendationInterventionRepository interventionRepository;
    @Mock
    private SpotRepository spotRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private AuditService auditService;

    private RecommendationInterventionService svc;

    @BeforeEach
    void setUp() {
        // save() 的 LambdaUpdateWrapper.set(方法引用) 需要 TableInfo 缓存（无 Spring 环境手动注册）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecommendationIntervention.class);
        svc = new RecommendationInterventionService(interventionRepository, spotRepository,
                communityUserService, auditService);
    }

    private Spot spot(String spotId, String city) {
        Spot s = new Spot();
        s.setSpotId(spotId);
        s.setCity(city);
        s.setStatus(Spot.STATUS_ONLINE);
        return s;
    }

    /* ---------- 权限 ---------- */

    @Test
    void save_nonAdmin_forbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.RECOMMEND_OPS));
        assertThrows(ForbiddenException.class, () -> svc.save("u1", Map.of()));
    }

    @Test
    void page_nonAdmin_forbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.RECOMMEND_OPS));
        assertThrows(ForbiddenException.class, () -> svc.page("u1", null, null, null, 1, 20));
    }

    /* ---------- 动作 × 对象矩阵与参数校验 ---------- */

    @Test
    void save_badTargetType_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "POI", "target_id", "x",
                        "action", "PIN", "reason", "原因")));
    }

    @Test
    void save_cityWithSpotAction_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "CITY", "target_id", "上海",
                        "action", "PIN", "reason", "原因")));
    }

    @Test
    void save_spotWithFeatured_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "SPOT", "target_id", "spot_x",
                        "action", "FEATURED", "reason", "原因")));
    }

    @Test
    void save_missingReason_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "CITY", "target_id", "上海",
                        "action", "FEATURED")));
    }

    @Test
    void save_timeWindowInverted_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "CITY", "target_id", "上海",
                        "action", "FEATURED", "reason", "原因",
                        "effective_from", "2026-09-20 08:00",
                        "effective_until", "2026-09-10 08:00")));
    }

    /* ---------- SPOT 预检 ---------- */

    @Test
    void save_spotNotExists_rejected() {
        when(spotRepository.selectOne(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> svc.save("admin", Map.of("target_type", "SPOT", "target_id", "spot_x",
                        "action", "PIN", "reason", "原因")));
    }

    /* ---------- 正常链路 ---------- */

    @Test
    void save_create_persistsAndAudits() {
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩", "上海"));
        when(interventionRepository.selectOne(any())).thenReturn(null);

        Map<String, Object> r = svc.save("admin", Map.of(
                "target_type", "SPOT", "target_id", "spot_上海_外滩",
                "action", "PIN", "reason", "运营周推"));

        assertEquals("created", r.get("mode"));
        ArgumentCaptor<RecommendationIntervention> captor =
                ArgumentCaptor.forClass(RecommendationIntervention.class);
        verify(interventionRepository).insert(captor.capture());
        assertEquals("PIN", captor.getValue().getAction());
        assertEquals("运营周推", captor.getValue().getReason());
        verify(auditService, times(1)).record(eq("admin"), eq(AuditLog.CAT_ADMIN),
                eq("recommendation_intervened"), anyString(), anyString(), any());
    }

    @Test
    void save_existing_overwritesWindowAndReason() {
        RecommendationIntervention existing = new RecommendationIntervention();
        existing.setId(9L);
        existing.setTargetType("SPOT");
        existing.setTargetId("spot_上海_外滩");
        existing.setAction("PIN");
        when(spotRepository.selectOne(any())).thenReturn(spot("spot_上海_外滩", "上海"));
        when(interventionRepository.selectOne(any())).thenReturn(existing);

        Map<String, Object> r = svc.save("admin", Map.of(
                "target_type", "SPOT", "target_id", "spot_上海_外滩",
                "action", "PIN", "reason", "改期运营"));

        assertEquals("updated", r.get("mode"));
        verify(interventionRepository, never()).insert(any());
        verify(interventionRepository).update(eq(null), any());
    }

    @Test
    void save_cityFeatured_creates() {
        when(interventionRepository.selectOne(any())).thenReturn(null);
        Map<String, Object> r = svc.save("admin", Map.of(
                "target_type", "CITY", "target_id", "上海",
                "action", "FEATURED", "reason", "本周目的地推荐"));

        assertEquals("created", r.get("mode"));
        ArgumentCaptor<RecommendationIntervention> captor =
                ArgumentCaptor.forClass(RecommendationIntervention.class);
        verify(interventionRepository).insert(captor.capture());
        assertEquals("CITY", captor.getValue().getTargetType());
    }

    @Test
    void remove_deletesAndAudits() {
        RecommendationIntervention row = new RecommendationIntervention();
        row.setId(5L);
        row.setTargetType("SPOT");
        row.setTargetId("spot_上海_外滩");
        row.setAction("DEMOTE");
        when(interventionRepository.selectById(5L)).thenReturn(row);

        svc.remove("admin", 5L);

        verify(interventionRepository).deleteById(5L);
        verify(auditService, times(1)).record(eq("admin"), eq(AuditLog.CAT_ADMIN),
                eq("recommendation_intervention_removed"), anyString(), anyString(), any());
    }

    @Test
    void remove_missing_noop() {
        when(interventionRepository.selectById(99L)).thenReturn(null);
        svc.remove("admin", 99L);
        verify(interventionRepository, never()).deleteById(99L);
        verify(auditService, never()).record(anyString(), any(), anyString(), anyString(), anyString(), any());
    }
}
