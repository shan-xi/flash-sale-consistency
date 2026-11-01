package com.example.flashsale.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${app.redis.host}") String host,
            @Value("${app.redis.port}") int port,
            @Value("${app.redis.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${app.redis.command-timeout-ms}") int commandTimeoutMs,
            @Value("${app.redis.retry-attempts}") int retryAttempts,
            @Value("${app.redis.retry-interval-ms}") int retryIntervalMs
    ) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(connectTimeoutMs)
                .setTimeout(commandTimeoutMs)
                .setRetryAttempts(retryAttempts)
                .setRetryInterval(retryIntervalMs);

        return Redisson.create(config);
    }
}
