package com.yuntu.tripplanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务配置（阶段四收尾：热度预聚合调度，设计方案 §7.3）。
 *
 * <p>与 {@link AsyncConfig} 分开：{@code @Async} 走业务异步池，调度池只跑轻量聚合任务，
 * 两类负载互不抢占。
 *
 * <p>池大小给 2 是留余量；当前只注册了一个聚合调度方法，Spring 调度对
 * 「同一个 @Scheduled 方法」本就串行执行，不会出现同一张日表被并发 DELETE/INSERT。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("trip-sched-");
        // 关闭时等待正在跑的聚合收尾，避免"跑到一半被 kill"留下半张表的数据
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
