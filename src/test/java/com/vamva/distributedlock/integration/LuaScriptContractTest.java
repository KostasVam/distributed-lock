package com.vamva.distributedlock.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests that verify Lua script behavior against real Redis.
 * These tests validate the atomic guarantees of each script independently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LuaScriptContractTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Nested
    class AcquireScript {

        private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

        {
            script.setLocation(new ClassPathResource("scripts/acquire.lua"));
            script.setResultType(Long.class);
        }

        @Test
        void acquiresFreeLock() {
            Long result = redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:1"), "token-1", "30000");

            assertEquals(1L, result, "Should acquire free lock");
        }

        @Test
        void failsWhenLockAlreadyHeld() {
            redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:2"), "token-1", "30000");

            Long result = redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:2"), "token-2", "30000");

            assertEquals(0L, result, "Should fail when lock is held");
        }

        @Test
        void setsTtlOnAcquire() {
            redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:3"), "token-1", "30000");

            Long ttl = redisTemplate.getExpire("lock:contract:3");
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 30, "TTL should be set (in seconds)");
        }

        @Test
        void storesOwnerToken() {
            redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:4"), "my-owner-token", "30000");

            String value = redisTemplate.opsForValue().get("lock:contract:4");
            assertEquals("my-owner-token", value);
        }

        @Test
        void differentKeysAreIndependent() {
            Long r1 = redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:a"), "token-1", "30000");
            Long r2 = redisTemplate.execute(script,
                    Collections.singletonList("lock:contract:b"), "token-2", "30000");

            assertEquals(1L, r1);
            assertEquals(1L, r2);
        }
    }

    @Nested
    class ReleaseScript {

        private final DefaultRedisScript<Long> acquireScript = new DefaultRedisScript<>();
        private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>();

        {
            acquireScript.setLocation(new ClassPathResource("scripts/acquire.lua"));
            acquireScript.setResultType(Long.class);
            releaseScript.setLocation(new ClassPathResource("scripts/release.lua"));
            releaseScript.setResultType(Long.class);
        }

        @Test
        void ownerCanRelease() {
            redisTemplate.execute(acquireScript,
                    Collections.singletonList("lock:release:1"), "token-1", "30000");

            Long result = redisTemplate.execute(releaseScript,
                    Collections.singletonList("lock:release:1"), "token-1");

            assertEquals(1L, result, "Owner should release lock");
            assertNull(redisTemplate.opsForValue().get("lock:release:1"), "Key should be deleted");
        }

        @Test
        void nonOwnerCannotRelease() {
            redisTemplate.execute(acquireScript,
                    Collections.singletonList("lock:release:2"), "token-1", "30000");

            Long result = redisTemplate.execute(releaseScript,
                    Collections.singletonList("lock:release:2"), "token-wrong");

            assertEquals(0L, result, "Non-owner should not release");
            assertNotNull(redisTemplate.opsForValue().get("lock:release:2"), "Key should still exist");
        }

        @Test
        void releaseNonExistentKeyReturnsZero() {
            Long result = redisTemplate.execute(releaseScript,
                    Collections.singletonList("lock:release:nonexistent"), "token-1");

            assertEquals(0L, result);
        }
    }

    @Nested
    class RenewScript {

        private final DefaultRedisScript<Long> acquireScript = new DefaultRedisScript<>();
        private final DefaultRedisScript<Long> renewScript = new DefaultRedisScript<>();

        {
            acquireScript.setLocation(new ClassPathResource("scripts/acquire.lua"));
            acquireScript.setResultType(Long.class);
            renewScript.setLocation(new ClassPathResource("scripts/renew.lua"));
            renewScript.setResultType(Long.class);
        }

        @Test
        void ownerCanRenew() {
            redisTemplate.execute(acquireScript,
                    Collections.singletonList("lock:renew:1"), "token-1", "5000");

            Long result = redisTemplate.execute(renewScript,
                    Collections.singletonList("lock:renew:1"), "token-1", "60000");

            assertEquals(1L, result, "Owner should renew lock");

            Long ttl = redisTemplate.getExpire("lock:renew:1");
            assertTrue(ttl > 5, "TTL should be extended beyond original");
        }

        @Test
        void nonOwnerCannotRenew() {
            redisTemplate.execute(acquireScript,
                    Collections.singletonList("lock:renew:2"), "token-1", "30000");

            Long result = redisTemplate.execute(renewScript,
                    Collections.singletonList("lock:renew:2"), "token-wrong", "60000");

            assertEquals(0L, result, "Non-owner should not renew");
        }

        @Test
        void renewNonExistentKeyReturnsZero() {
            Long result = redisTemplate.execute(renewScript,
                    Collections.singletonList("lock:renew:nonexistent"), "token-1", "60000");

            assertEquals(0L, result);
        }
    }
}
