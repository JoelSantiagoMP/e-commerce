package com.tienda.telegram.config;

import com.tienda.telegram.service.TelegramClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramWebhookInitializer {

    private static final String TEST_TOKEN = "test_token";

    private final TelegramClientService telegramClientService;

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    @Value("${telegram.bot.webhook-secret-token}")
    private String webhookSecretToken;

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhookOnStartup() {
        if (botToken == null || botToken.isBlank() || TEST_TOKEN.equals(botToken)) {
            log.warn("Webhook de Telegram omitido: TELEGRAM_BOT_TOKEN no configurado.");
            return;
        }

        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            log.warn("Webhook de Telegram omitido: APP_BASE_URL no configurado.");
            return;
        }

        String normalizedBaseUrl = appBaseUrl.replaceAll("/+$", "");
        String webhookUrl = normalizedBaseUrl + "/api/v1/telegram/webhook";

        log.info("Registrando webhook de Telegram en: {}", webhookUrl);
        boolean registered = telegramClientService.setWebhook(webhookUrl, webhookSecretToken);

        if (!registered) {
            log.error("Falló el auto-registro del webhook de Telegram.");
        }
    }
}
