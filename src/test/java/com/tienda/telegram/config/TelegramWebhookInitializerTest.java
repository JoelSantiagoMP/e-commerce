package com.tienda.telegram.config;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tienda.telegram.service.TelegramClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TelegramWebhookInitializerTest {

    private static final String WEBHOOK_URL = "https://example.com/api/v1/telegram/webhook";
    private static final String WEBHOOK_SECRET = "prod-webhook-secret";

    @Mock
    private TelegramClientService telegramClientService;

    private TelegramWebhookInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new TelegramWebhookInitializer(telegramClientService);
        ReflectionTestUtils.setField(initializer, "botToken", "123456:ABC-DEF");
        ReflectionTestUtils.setField(initializer, "appBaseUrl", "https://example.com");
        ReflectionTestUtils.setField(initializer, "webhookSecretToken", WEBHOOK_SECRET);
    }

    @Test
    void registerWebhookOnStartup_validConfig_registersWebhookWithSecretToken() {
        when(telegramClientService.setWebhook(WEBHOOK_URL, WEBHOOK_SECRET)).thenReturn(true);

        initializer.registerWebhookOnStartup();

        verify(telegramClientService).setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);
    }

    @Test
    void registerWebhookOnStartup_normalizesTrailingSlashInBaseUrl() {
        ReflectionTestUtils.setField(initializer, "appBaseUrl", "https://example.com/");
        when(telegramClientService.setWebhook(WEBHOOK_URL, WEBHOOK_SECRET)).thenReturn(true);

        initializer.registerWebhookOnStartup();

        verify(telegramClientService).setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);
    }

    @Test
    void registerWebhookOnStartup_testToken_skipsRegistration() {
        ReflectionTestUtils.setField(initializer, "botToken", "test_token");

        initializer.registerWebhookOnStartup();

        verify(telegramClientService, never()).setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);
    }

    @Test
    void registerWebhookOnStartup_missingBaseUrl_skipsRegistration() {
        ReflectionTestUtils.setField(initializer, "appBaseUrl", "");

        initializer.registerWebhookOnStartup();

        verify(telegramClientService, never()).setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);
    }

    @Test
    void registerWebhookOnStartup_renderExternalUrl_registersWebhook() {
        ReflectionTestUtils.setField(initializer, "appBaseUrl", "https://e-commerce-backend.onrender.com");

        String renderWebhookUrl = "https://e-commerce-backend.onrender.com/api/v1/telegram/webhook";
        when(telegramClientService.setWebhook(renderWebhookUrl, WEBHOOK_SECRET)).thenReturn(true);

        initializer.registerWebhookOnStartup();

        verify(telegramClientService).setWebhook(renderWebhookUrl, WEBHOOK_SECRET);
    }
}
