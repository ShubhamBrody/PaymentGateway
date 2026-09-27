package com.paymentsystem.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

// Each Spring test context that imports this gets its own Redis container, so worker threads never share queues.
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestContainerConfig {

    // Spring starts the container before the context uses it and stops it when the context closes.
    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }
}
