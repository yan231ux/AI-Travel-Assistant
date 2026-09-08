package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.AbAssignment;
import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.repository.AbAssignmentRepository;
import com.yuntu.tripplanner.repository.AbExperimentRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A/B 实验服务单测（阶段四任务 6）：
 * 创建校验（命名/参数/同作用域唯一 ACTIVE）/ 关闭后停止分桶 /
 * 粘性分桶（已落桶用户反复 resolve 不变更，幂等）/ 无实验或匿名 → null。
 */
@ExtendWith(MockitoExtension.class)
class AbExperimentServiceTest {

    @Mock
    private AbExperimentRepository experimentRepository;
    @Mock
    private AbAssignmentRepository assignmentRepository;

    private AbExperimentService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AbExperiment.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AbAssignment.class);
        service = new AbExperimentService(experimentRepository, assignmentRepository);
    }

    private AbExperiment activeExp(String name, String feedType, String strategy,
                                   int traffic, int control) {
        AbExperiment e = new AbExperiment();
        e.setExpName(name);
        e.setFeedType(feedType);
        e.setStrategy(strategy);
        e.setStatus(AbExperiment.STATUS_ACTIVE);
        e.setTrafficPercent(traffic);
        e.setControlPercent(control);
        return e;
    }

    @Test
    void create_validExperiment_persists() {
        when(experimentRepository.selectOne(any())).thenReturn(null);
        when(experimentRepository.selectCount(any())).thenReturn(0L);

        AbExperiment exp = service.create("spot_feed_quality_gate", "质量门实验",
                AbExperiment.FEED_SPOT, AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50);

        assertEquals(AbExperiment.STATUS_ACTIVE, exp.getStatus());
        assertEquals(100, exp.getTrafficPercent());
    }

    @Test
    void create_duplicateName_rejected() {
        when(experimentRepository.selectOne(any()))
                .thenReturn(activeExp("dup", AbExperiment.FEED_SPOT,
                        AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));

        assertThrows(IllegalArgumentException.class, () -> service.create("dup", null,
                AbExperiment.FEED_SPOT, AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));
    }

    @Test
    void create_secondActiveForSameFeedType_rejected() {
        when(experimentRepository.selectOne(any())).thenReturn(null);
        when(experimentRepository.selectCount(any())).thenReturn(1L); // 同 feed_type 已有 ACTIVE

        assertThrows(IllegalArgumentException.class, () -> service.create("another", null,
                AbExperiment.FEED_SPOT, AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));
    }

    @Test
    void create_mismatchedStrategyAndFeedType_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.create("bad", null,
                AbExperiment.FEED_POST, AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));
        assertThrows(IllegalArgumentException.class, () -> service.create("bad2", null,
                "UNKNOWN", AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));
    }

    @Test
    void anonymousOrUnknownExperiment_returnsNull() {
        assertNull(service.resolveVariant(null, "any"));
        when(experimentRepository.selectOne(any())).thenReturn(null);
        assertNull(service.resolveVariant("u1", "not_exist"));
    }

    @Test
    void closedExperiment_noLongerBuckets() {
        AbExperiment closed = activeExp("old", AbExperiment.FEED_SPOT,
                AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50);
        closed.setStatus(AbExperiment.STATUS_CLOSED);
        when(experimentRepository.selectOne(any())).thenReturn(closed);

        assertNull(service.resolveVariant("u1", "old"));
    }

    @Test
    void resolveVariant_isStickyAndIdempotent() {
        when(experimentRepository.selectOne(any())).thenReturn(activeExp("exp",
                AbExperiment.FEED_SPOT, AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50));
        // 第一次：无既有分桶 → 落桶并落行
        when(assignmentRepository.selectOne(any())).thenReturn(null);

        String v1 = service.resolveVariant("u7", "exp");
        assertNotNull(v1);

        // 第二次：已有分桶行 → 直接复用，不再 insert（粘性）
        when(assignmentRepository.selectOne(any())).thenReturn(rowOf("u7", "exp", v1));
        String v2 = service.resolveVariant("u7", "exp");
        assertEquals(v1, v2);
        verify(assignmentRepository, times(1)).insert(any());
    }

    @Test
    void resolveVariant_outOfTraffic_notRecorded() {
        // 1% 流量：绝大多数用户不参与 → 不落行、返回 null
        when(experimentRepository.selectOne(any())).thenReturn(activeExp("tiny",
                AbExperiment.FEED_POST, AbExperimentService.STRATEGY_LOW_QUALITY_FILTER, 1, 50));
        when(assignmentRepository.selectOne(any())).thenReturn(null);

        int participated = 0;
        for (int i = 0; i < 300; i++) {
            if (service.resolveVariant("u" + i, "tiny") != null) {
                participated++;
            }
        }
        assertTrue(participated < 20, "participated=" + participated);
        verify(assignmentRepository, times(participated)).insert(any());
    }

    @Test
    void listAll_attachesBucketCounts() {
        AbExperiment e = activeExp("exp", AbExperiment.FEED_SPOT,
                AbExperimentService.STRATEGY_QUALITY_GATE, 100, 50);
        when(experimentRepository.selectList(any())).thenReturn(List.of(e));
        when(assignmentRepository.selectCount(any())).thenReturn(12L);

        var stats = service.listAll();
        assertEquals(1, stats.size());
        assertEquals(12L, stats.get(0).controlUsers());
        assertEquals(12L, stats.get(0).treatmentUsers());
    }

    private AbAssignment rowOf(String userId, String expName, String variant) {
        AbAssignment a = new AbAssignment();
        a.setUserId(userId);
        a.setExpName(expName);
        a.setVariant(variant);
        return a;
    }
}
