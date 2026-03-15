package com.vamva.distributedlock.token;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TokenGeneratorTest {

    private final TokenGenerator generator = new TokenGenerator();

    @Test
    void generatesNonNullToken() {
        String token = generator.generate();
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void generatesUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(generator.generate());
        }
        assertEquals(1000, tokens.size(), "All tokens should be unique");
    }

    @Test
    void tokenIsUuidFormat() {
        String token = generator.generate();
        // UUID format: 8-4-4-4-12
        assertTrue(token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
