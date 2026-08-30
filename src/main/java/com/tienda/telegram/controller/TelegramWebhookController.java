package com.tienda.telegram.controller;

import com.tienda.telegram.dto.TelegramUpdateDTO;
import com.tienda.telegram.service.TelegramBotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telegram")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramBotService telegramBotService;

    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(@RequestBody TelegramUpdateDTO update) {
        telegramBotService.processUpdate(update);
        return ResponseEntity.ok().build();
    }
}
