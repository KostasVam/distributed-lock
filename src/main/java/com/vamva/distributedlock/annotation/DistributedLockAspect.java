package com.vamva.distributedlock.annotation;

import com.vamva.distributedlock.api.DistributedLockClient;
import com.vamva.distributedlock.api.LockAcquisitionException;
import com.vamva.distributedlock.model.LockRequest;
import com.vamva.distributedlock.model.LockResult;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts methods annotated with {@link DistributedLock}.
 *
 * <p>Acquires the lock before method execution and releases it in a finally block.
 * Supports SpEL expressions in the key attribute for dynamic resource key resolution.</p>
 */
@Aspect
@Slf4j
public class DistributedLockAspect {

    private final DistributedLockClient lockClient;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(DistributedLockClient lockClient) {
        this.lockClient = lockClient;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String resourceKey = resolveKey(joinPoint, distributedLock.key());

        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException(
                    "@DistributedLock key resolved to blank for method: " +
                    joinPoint.getSignature().toShortString());
        }

        LockRequest request = LockRequest.builder()
                .resourceKey(resourceKey)
                .leaseMs(distributedLock.leaseMs())
                .waitTimeoutMs(distributedLock.waitTimeoutMs())
                .build();

        LockResult lockResult = lockClient.acquire(request);

        if (!lockResult.isAcquired()) {
            throw new LockAcquisitionException(
                    "Failed to acquire lock for @DistributedLock resource: " + resourceKey);
        }

        try {
            return joinPoint.proceed();
        } finally {
            boolean released = lockClient.release(lockResult.getResourceKey(), lockResult.getLockToken());
            if (!released) {
                log.warn("Failed to release @DistributedLock resource_key={} (may have expired)", resourceKey);
            }
        }
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, joinPoint.getArgs(), parameterNameDiscoverer);

        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}
