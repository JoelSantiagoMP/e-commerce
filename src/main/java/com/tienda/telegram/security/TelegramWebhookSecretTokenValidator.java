package com.tienda.telegram.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramWebhookSecretTokenValidator {

    public static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final String expectedSecretToken;

    public TelegramWebhookSecretTokenValidator(
            @Value("${telegram.bot.webhook-secret-token}") String expectedSecretToken) {
        this.expectedSecretToken = expectedSecretToken;
    }

    public boolean isValid(String providedToken) {
        if (providedToken == null || providedToken.isBlank()) {
            return false;
        }
        return expectedSecretToken.equals(providedToken);
    }
}
