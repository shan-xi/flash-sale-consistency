package com.example.flashsale;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Skeleton for the next interview exercise.
 *
 * It intentionally uses GenericContainer for Redis instead of a separate
 * testcontainers-redis artifact.
 */
@Testcontainers
class InfrastructureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4")
                    .withExposedPorts(6379);

    @Test
    void containersStart() {
        assertTrue(postgres.isRunning());
        assertTrue(redis.isRunning());
        assertTrue(redis.getMappedPort(6379) > 0);
    }
}
