package com.tienda.telegram.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tienda.telegram.dto.TelegramUpdateDTO;
import com.tienda.telegram.service.TelegramBotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TelegramWebhookController.class)
class TelegramWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TelegramBotService telegramBotService;

    @Test
    void receiveWebhook_validUpdate_returnsOkAndProcessesUpdate() throws Exception {
        when(telegramBotService.processUpdate(any(TelegramUpdateDTO.class)))
                .thenReturn("OK");

        String payload = """
                {
                  "update_id": 1001,
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
                """;

        mockMvc.perform(post("/api/v1/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        verify(telegramBotService).processUpdate(any(TelegramUpdateDTO.class));
    }
}
