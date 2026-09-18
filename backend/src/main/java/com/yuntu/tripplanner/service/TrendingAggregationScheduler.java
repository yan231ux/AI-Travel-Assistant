package com.yuntu.tripplanner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 热度预聚合调度（设计方案 §7.3「后台定时聚合小时/天级统计，首页读取预聚合结果」）。
 *
 * <p><b>为什么需要它</b>：{@code spot_trending_daily} 等预聚合表此前只有聚合方法与单测，
 * <b>生产代码里没有任何触发者</b>——表长期为空，等于"路修好了没通车"。本类补上两个触发点：
 * <ol>
 *   <li><b>每日兜底</b>：凌晨重算「昨天 + 今天」。昨天是收口（事件已写满），
 *       今天是让当天统计逐次收敛（当天事件是逐步写入的）。</li>
 *   <li><b>启动补跑</b>：应用就绪后滚动重算最近 N 天，覆盖「停机期间漏跑」的日期。
 *       聚合是幂等的 DELETE + INSERT...SELECT，重复执行无副作用。</li>
 * </ol>
 *
 * <p><b>失败隔离</b>：聚合异常一律不外抛——定时任务抛异常只会被调度器吞掉，
 * 还会让同批次的其它维度一起中断；这里逐维度收敛成日志。
 */
@Slf4j
@Component
public class TrendingAggregationScheduler {

    private final TravelEventService travelEventService;
    /** 总开关：本地调试/测试可关（trending.aggregation.enabled=false） */
    private final boolean enabled;
    /** 启动补跑窗口天数：越大启动越慢，默认 7 */
    private final int backfillDays;

    public TrendingAggregationScheduler(
            TravelEventService travelEventService,
            @Value("${trending.aggregation.enabled:true}") boolean enabled,
            @Value("${trending.aggregation.backfill-days:7}") int backfillDays) {
        this.travelEventService = travelEventService;
        this.enabled = enabled;
        this.backfillDays = backfillDays;
    }

    /**
     * 每日 03:20 兜底重算「昨天 + 今天」。
     * cron 可配（trending.aggregation.cron），默认避开业务高峰。
     */
    @Scheduled(cron = "${trending.aggregation.cron:0 20 3 * * ?}")
    public void nightlyAggregate() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now();
        int ok = safeAggregate(today.minusDays(1)) + safeAggregate(today);
        log.info("每日热度预聚合完成：日期 {}/{}，成功维度 {} 个", today.minusDays(1), today, ok);
    }

    /**
     * 应用就绪后补跑最近 N 天。
     *
     * <p>用 {@link ApplicationReadyEvent}：此时数据源与表结构（含存量库自动建表）都已就绪；
     * 窗口内的聚合量很小（N × 3 条 SQL，均走 stat_date 索引），同步执行不影响可用性。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!enabled || backfillDays <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        int total = 0;
        for (int i = 0; i < backfillDays; i++) {
            total += safeAggregate(today.minusDays(i));
        }
        log.info("启动补跑热度预聚合完成：窗口 {} 天（{} ~ {}），累计成功维度 {} 个",
                backfillDays, today.minusDays(backfillDays - 1L), today, total);
    }

    /** 单日聚合，异常不出本方法 */
    private int safeAggregate(LocalDate date) {
        try {
            return travelEventService.aggregateAll(date);
        } catch (Exception e) {
            log.warn("热度预聚合失败（date={}）: {}", date, e.getMessage());
            return 0;
        }
    }
}
