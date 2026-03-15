package com.vamva.distributedlock.token;

import java.util.UUID;

/**
 * Default {@link TokenGenerator} implementation using UUID v4.
 *
 * <p>Produces collision-resistant tokens with no coordination required.
 * Override by declaring a custom {@code TokenGenerator} bean.</p>
 */
public class UuidTokenGenerator implements TokenGenerator {

    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
