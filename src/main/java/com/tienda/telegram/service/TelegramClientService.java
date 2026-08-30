package com.tienda.telegram.service;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class TelegramClientService {

    private final RestTemplate restTemplate;
    private final String botToken;
    private final String apiUrl;

    public TelegramClientService(
            RestTemplate restTemplate,
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.api-url}") String apiUrl) {
        this.restTemplate = restTemplate;
        this.botToken = botToken;
        this.apiUrl = apiUrl;
    }

    public void sendMessage(Long chatId, String text) {
        String url = apiUrl + botToken + "/sendMessage";

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "text", text
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForEntity(url, request, String.class);
            log.info("Mensaje enviado a Telegram chatId={}", chatId);
        } catch (HttpClientErrorException ex) {
            log.error("Error HTTP al enviar mensaje a Telegram chatId={}: status={}, body={}",
                    chatId, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (ResourceAccessException ex) {
            log.error("Error de red al enviar mensaje a Telegram chatId={}: {}", chatId, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Error inesperado al enviar mensaje a Telegram chatId={}: {}", chatId, ex.getMessage(), ex);
        }
    }
}
