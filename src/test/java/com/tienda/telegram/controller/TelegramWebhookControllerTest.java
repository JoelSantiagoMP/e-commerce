package com.tienda.telegram.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tienda.telegram.dto.TelegramUpdateDTO;
import com.tienda.telegram.security.TelegramWebhookSecretTokenValidator;
import com.tienda.telegram.service.TelegramBotService;
import com.tienda.telegram.service.TelegramUpdateDeduplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(TelegramWebhookController.class)
@Import(TelegramWebhookSecretTokenValidator.class)
@TestPropertySource(properties = "telegram.bot.webhook-secret-token=test_webhook_secret")
class TelegramWebhookControllerTest {

    private static final String VALID_SECRET = "test_webhook_secret";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TelegramBotService telegramBotService;

    @MockBean
    private TelegramUpdateDeduplicationService deduplicationService;

    @Test
    void receiveWebhook_validUpdate_returnsOkAndProcessesUpdate() throws Exception {
        when(deduplicationService.isDuplicate(1001L)).thenReturn(false);
        when(telegramBotService.processUpdate(any(TelegramUpdateDTO.class)))
                .thenReturn("OK");

        mockMvc.perform(webhookPost(validPayload(1001L), VALID_SECRET))
                .andExpect(status().isOk());

        verify(telegramBotService).processUpdate(any(TelegramUpdateDTO.class));
    }

    @Test
    void receiveWebhook_duplicateUpdate_returnsOkWithoutProcessing() throws Exception {
        when(deduplicationService.isDuplicate(2002L)).thenReturn(true);

        mockMvc.perform(webhookPost(validPayload(2002L), VALID_SECRET))
                .andExpect(status().isOk());

        verify(telegramBotService, never()).processUpdate(any(TelegramUpdateDTO.class));
    }

    @Test
    void receiveWebhook_missingSecretToken_returnsForbiddenWithoutProcessing() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(3003L)))
                .andExpect(status().isForbidden());

        verify(telegramBotService, never()).processUpdate(any(TelegramUpdateDTO.class));
        verify(deduplicationService, never()).isDuplicate(any());
    }

    @Test
    void receiveWebhook_invalidSecretToken_returnsForbiddenWithoutProcessing() throws Exception {
        mockMvc.perform(webhookPost(validPayload(4004L), "invalid-secret"))
                .andExpect(status().isForbidden());

        verify(telegramBotService, never()).processUpdate(any(TelegramUpdateDTO.class));
        verify(deduplicationService, never()).isDuplicate(any());
    }

    private MockHttpServletRequestBuilder webhookPost(String payload, String secretToken) {
        return post("/api/v1/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(TelegramWebhookSecretTokenValidator.SECRET_TOKEN_HEADER, secretToken);
    }

    private String validPayload(long updateId) {
        return """
                {
                  "update_id": %d,
                  "message": {
                    "message_id": 42,
                    "chat": {
                      "id": 123456789,
                      "first_name": "Joel",
                      "username": "joel_m"
                    },
                    "text": "/start"
                  }
                }
                """.formatted(updateId);
    }
}
