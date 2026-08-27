package com.yuntu.tripplanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步配置类
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trip-async-");
        executor.initialize();
        return executor;
    }

    /**
     * 工具执行线程池：Agent 并行调用数据源、地图信息补全共用，
     * 避免阻塞 HTTP I/O 挂在 ForkJoinPool.commonPool 上。
     */
    @Bean(name = "toolExecutor")
    public Executor toolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tool-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Agent 生成执行线程池（SSE 流式端点用）。
     *
     * 与 toolExecutor 分离：生成任务是长阻塞型（LLM 最多 60s/次），
     * 若复用到 toolExecutor 会占满线程导致内部工具任务排队超时。
     * 默认 waitForTasksToCompleteOnShutdown=false，dev 重启不被长生成卡住。
     */
    @Bean(name = "agentExecutor")
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("agent-sse-");
        executor.initialize();
        return executor;
    }
}