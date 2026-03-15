package com.vamva.distributedlock.token;

import java.util.UUID;

/**
 * Generates unique owner tokens for lock acquisition.
 *
 * <p>Each token uniquely identifies a lock holder, ensuring that only the
 * original acquirer can renew or release the lock.</p>
 */
public class TokenGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
