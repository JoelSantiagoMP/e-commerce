package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class TelegramClientServiceTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF";
    private static final String API_URL = "https://api.telegram.org/bot";
    private static final String WEBHOOK_URL = "https://example.com/api/v1/telegram/webhook";
    private static final String WEBHOOK_SECRET = "prod-webhook-secret";

    @Mock
    private RestTemplate restTemplate;

    private TelegramClientService telegramClientService;

    @BeforeEach
    void setUp() {
        telegramClientService = new TelegramClientService(restTemplate, BOT_TOKEN, API_URL);
    }

    @Test
    void setWebhook_includesUrlAndSecretTokenInPayload() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"ok\":true}"));

        boolean registered = telegramClientService.setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);

        assertTrue(registered);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq(API_URL + BOT_TOKEN + "/setWebhook"),
                requestCaptor.capture(),
                eq(String.class));

        Map<String, Object> payload = requestCaptor.getValue().getBody();
        assertEquals(WEBHOOK_URL, payload.get("url"));
        assertEquals(WEBHOOK_SECRET, payload.get("secret_token"));
    }

    @Test
    void setWebhook_apiFailure_returnsFalse() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.internalServerError().build());

        boolean registered = telegramClientService.setWebhook(WEBHOOK_URL, WEBHOOK_SECRET);

        assertFalse(registered);
    }
}
