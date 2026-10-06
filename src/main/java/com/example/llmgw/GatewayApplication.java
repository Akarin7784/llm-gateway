package com.example.llmgw;

import com.example.llmgw.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService streamWorkers() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Heartbeats are small periodic writes, so a couple of platform threads cover a lot of streams;
     * the pool size is the knob to turn if heartbeat cadence shows up in the latency histogram.
     */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService streamHeartbeats() {
        return Executors.newScheduledThreadPool(2);
    }
}
