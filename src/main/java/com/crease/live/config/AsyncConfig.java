package com.crease.live.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /** Worker pool that processes balls for many live matches in parallel. */
    @Bean(name = "simulationExecutor")
    public ThreadPoolTaskExecutor simulationExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        int cores = Runtime.getRuntime().availableProcessors();
        ex.setCorePoolSize(Math.max(2, cores));
        ex.setMaxPoolSize(Math.max(4, cores * 2));
        ex.setQueueCapacity(1_000);
        ex.setThreadNamePrefix("sim-");
        ex.initialize();
        return ex;
    }

    /** Pool that fans out real-time updates to SSE subscribers off the scoring threads. */
    @Bean(name = "broadcastExecutor")
    public Executor broadcastExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(5_000);
        ex.setThreadNamePrefix("push-");
        ex.initialize();
        return ex;
    }
}
