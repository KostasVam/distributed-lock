package com.vamva.distributedlock.token;

/**
 * Generates unique owner tokens for lock acquisition.
 *
 * <p>Each token uniquely identifies a lock holder, ensuring that only the
 * original acquirer can renew or release the lock.</p>
 *
 * <p>The default implementation uses UUID v4. Consumers can override this
 * by declaring their own {@code TokenGenerator} bean (e.g., ULID, sequential,
 * or tokens with embedded metadata for debugging).</p>
 */
public interface TokenGenerator {

    /**
     * Generates a unique owner token.
     *
     * @return a non-null, non-blank unique token string
     */
    String generate();
}
