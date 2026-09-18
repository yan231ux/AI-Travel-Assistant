package com.yuntu.tripplanner.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 热度预聚合调度单测（设计方案 §7.3）。
 *
 * <p><b>为什么值得单独测</b>：预聚合方法此前在生产代码里<b>零调用方</b>——
 * 方法和表都写好了，就是没人触发，日表常年为空。这类"方法在、没人调"的退化，
 * 普通业务单测发现不了（方法本身测得再绿也没用）。本类锁住"确实被调度触发"这一环。
 */
@ExtendWith(MockitoExtension.class)
class TrendingAggregationSchedulerTest {

    @Mock
    private TravelEventService travelEventService;

    private TrendingAggregationScheduler scheduler(boolean enabled, int backfillDays) {
        return new TrendingAggregationScheduler(travelEventService, enabled, backfillDays);
    }

    @Test
    void backfillOnStartup_aggregatesWholeWindowIncludingToday() {
        scheduler(true, 7).backfillOnStartup();

        ArgumentCaptor<LocalDate> cap = ArgumentCaptor.forClass(LocalDate.class);
        verify(travelEventService, times(7)).aggregateAll(cap.capture());
        List<LocalDate> dates = cap.getAllValues();
        LocalDate today = LocalDate.now();
        assertEquals(today, dates.get(0), "补跑从今天开始");
        assertEquals(today.minusDays(6), dates.get(6), "覆盖最近 7 天（含今天）");
    }

    @Test
    void backfillOnStartup_skippedWhenDisabled() {
        scheduler(false, 7).backfillOnStartup();

        verifyNoInteractions(travelEventService);
    }

    @Test
    void backfillOnStartup_skippedWhenWindowNotPositive() {
        scheduler(true, 0).backfillOnStartup();

        verifyNoInteractions(travelEventService);
    }

    @Test
    void nightlyAggregate_recomputesYesterdayThenToday() {
        scheduler(true, 7).nightlyAggregate();

        ArgumentCaptor<LocalDate> cap = ArgumentCaptor.forClass(LocalDate.class);
        verify(travelEventService, times(2)).aggregateAll(cap.capture());
        LocalDate today = LocalDate.now();
        assertEquals(today.minusDays(1), cap.getAllValues().get(0), "先重算昨天（事件已写满，收口）");
        assertEquals(today, cap.getAllValues().get(1), "再重算今天（当天逐步收敛）");
    }

    @Test
    void nightlyAggregate_skippedWhenDisabled() {
        scheduler(false, 7).nightlyAggregate();

        verifyNoInteractions(travelEventService);
    }

    /**
     * 聚合异常不得从定时任务里抛出：抛出去只会被调度器吞掉，
     * 还会让同批次的其余维度（以及后续日期）一起中断。
     */
    @Test
    void aggregateFailure_isSwallowedNotPropagated() {
        when(travelEventService.aggregateAll(any(LocalDate.class)))
                .thenThrow(new RuntimeException("Table 'spot_trending_daily' doesn't exist"));

        assertDoesNotThrow(() -> scheduler(true, 3).backfillOnStartup());
        assertDoesNotThrow(() -> scheduler(true, 3).nightlyAggregate());
    }
}
