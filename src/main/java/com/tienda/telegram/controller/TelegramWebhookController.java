package com.tienda.telegram.controller;

import com.tienda.telegram.dto.TelegramUpdateDTO;
import com.tienda.telegram.security.TelegramWebhookSecretTokenValidator;
import com.tienda.telegram.service.TelegramBotService;
import com.tienda.telegram.service.TelegramUpdateDeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/telegram")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramBotService telegramBotService;
    private final TelegramUpdateDeduplicationService deduplicationService;
    private final TelegramWebhookSecretTokenValidator secretTokenValidator;

    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestHeader(value = TelegramWebhookSecretTokenValidator.SECRET_TOKEN_HEADER, required = false)
            String secretToken,
            @RequestBody TelegramUpdateDTO update) {

        if (!secretTokenValidator.isValid(secretToken)) {
            log.warn("Webhook rechazado: secret token ausente o inválido");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (update != null && deduplicationService.isDuplicate(update.getUpdateId())) {
            return ResponseEntity.ok().build();
        }

        telegramBotService.processUpdate(update);
        return ResponseEntity.ok().build();
    }
}
