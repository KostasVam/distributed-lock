package com.vamva.distributedlock.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative distributed lock annotation for methods.
 *
 * <p>When placed on a method, a distributed lock is acquired before execution
 * and released after (in a finally block). If the lock cannot be acquired,
 * the method is not executed and a {@link com.vamva.distributedlock.api.LockAcquisitionException}
 * is thrown.</p>
 *
 * <p>The {@code key} attribute supports SpEL expressions for dynamic resource keys:</p>
 * <pre>{@code
 * @DistributedLock(key = "'job:' + #jobName")
 * public void runJob(String jobName) { ... }
 *
 * @DistributedLock(key = "'invoice:' + #id", leaseMs = 60000)
 * public void processInvoice(long id) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * The lock resource key. Supports SpEL expressions referencing method parameters.
     * Example: {@code "'job:daily-settlement'"} or {@code "'invoice:' + #id"}
     */
    String key();

    /** Lease duration in milliseconds. 0 means use global default. */
    long leaseMs() default 0;

    /** Maximum time in milliseconds to wait for lock acquisition. 0 means single attempt. */
    long waitTimeoutMs() default 0;
}
