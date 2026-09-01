package com.globalfutservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Outbound notifications run on virtual threads.
 *
 * <p>Sending a WhatsApp template or an order email is almost entirely waiting on someone
 * else's socket. On a platform thread pool that waiting occupies a real OS thread and a
 * slow upstream can starve the pool that also serves checkout. On virtual threads the
 * blocked carrier is released, so a Meta API outage costs latency on notifications and
 * nothing at all on the payment path.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean("applicationTaskExecutor")
    public Executor getAsyncExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public AsyncTaskExecutor virtualThreadExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
