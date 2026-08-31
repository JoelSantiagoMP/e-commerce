package com.tienda.telegram.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramWebhookSecretTokenValidatorTest {

    private static final String EXPECTED_SECRET = "test_webhook_secret";

    private TelegramWebhookSecretTokenValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TelegramWebhookSecretTokenValidator(EXPECTED_SECRET);
    }

    @Test
    void isValid_matchingToken_returnsTrue() {
        assertTrue(validator.isValid(EXPECTED_SECRET));
    }

    @Test
    void isValid_wrongToken_returnsFalse() {
        assertFalse(validator.isValid("wrong-secret"));
    }

    @Test
    void isValid_nullToken_returnsFalse() {
        assertFalse(validator.isValid(null));
    }

    @Test
    void isValid_blankToken_returnsFalse() {
        assertFalse(validator.isValid("   "));
    }
}
