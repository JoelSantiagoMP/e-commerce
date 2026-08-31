package com.tienda.telegram.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "Markdown");

        try {
            postToTelegram(url, payload, "sendMessage", chatId);
        } catch (Exception ex) {
            log.error("No se pudo enviar mensaje a Telegram chatId={}", chatId, ex);
        }
    }

    public void sendMessageWithInlineKeyboard(Long chatId, String text, Map<String, Object> inlineKeyboard) {
        String url = apiUrl + botToken + "/sendMessage";

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "Markdown");
        payload.put("reply_markup", inlineKeyboard);

        try {
            postToTelegram(url, payload, "sendMessage", chatId);
        } catch (Exception ex) {
            log.error("No se pudo enviar mensaje con teclado inline a Telegram chatId={}", chatId, ex);
        }
    }

    public void editMessageText(Long chatId, Long messageId, String text) {
        String url = apiUrl + botToken + "/editMessageText";

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "message_id", messageId,
                "text", text,
                "parse_mode", "Markdown"
        );

        try {
            postToTelegram(url, payload, "editMessageText", chatId);
        } catch (Exception ex) {
            log.error("No se pudo editar mensaje en Telegram chatId={}, messageId={}", chatId, messageId, ex);
        }
    }

    public void answerCallbackQuery(String callbackQueryId) {
        String url = apiUrl + botToken + "/answerCallbackQuery";

        Map<String, Object> payload = Map.of("callback_query_id", callbackQueryId);

        try {
            postToTelegram(url, payload, "answerCallbackQuery", null);
        } catch (Exception ex) {
            log.error("No se pudo responder callback query id={}", callbackQueryId, ex);
        }
    }

    public void editMessageTextWithInlineKeyboard(
            Long chatId, Long messageId, String text, Map<String, Object> inlineKeyboard) {
        String url = apiUrl + botToken + "/editMessageText";

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("message_id", messageId);
        payload.put("text", text);
        payload.put("parse_mode", "Markdown");
        payload.put("reply_markup", inlineKeyboard);

        try {
            postToTelegram(url, payload, "editMessageText", chatId);
        } catch (Exception ex) {
            log.error("No se pudo editar mensaje con teclado inline en Telegram chatId={}, messageId={}",
                    chatId, messageId, ex);
        }
    }

    public Map<String, Object> buildQuantitySelectorKeyboard(String sku) {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                Map.of("text", "1", "callback_data", "SELECT_QTY:" + sku + ":1"),
                                Map.of("text", "2", "callback_data", "SELECT_QTY:" + sku + ":2"),
                                Map.of("text", "4", "callback_data", "SELECT_QTY:" + sku + ":4"),
                                Map.of("text", "8", "callback_data", "SELECT_QTY:" + sku + ":8")
                        )
                )
        );
    }

    public Map<String, Object> buildOrderConfirmationKeyboard(String sku, int quantity) {
        return Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                Map.of(
                                        "text", "✅ Confirmar Pedido",
                                        "callback_data", "CONFIRM_ORDER:" + sku + ":" + quantity
                                ),
                                Map.of(
                                        "text", "❌ Cancelar",
                                        "callback_data", "CANCEL_ORDER"
                                )
                        )
                )
        );
    }

    /**
     * Teclado para catálogo: botones de compra por SKU y acceso rápido al catálogo completo.
     */
    public Map<String, Object> buildCatalogKeyboard(List<String> skus) {
        List<List<Map<String, String>>> rows = new ArrayList<>();

        for (int index = 0; index < skus.size(); index += 2) {
            List<Map<String, String>> row = new ArrayList<>();
            row.add(buildBuySkuButton(skus.get(index)));
            if (index + 1 < skus.size()) {
                row.add(buildBuySkuButton(skus.get(index + 1)));
            }
            rows.add(row);
        }

        rows.add(List.of(
                Map.of("text", "📋 Ver Catálogo Completo", "callback_data", "SHOW_CATALOG")
        ));

        return Map.of("inline_keyboard", rows);
    }

    /**
     * Teclado contextual tras consultas Gemini: compra del SKU y catálogo.
     */
    public Map<String, Object> buildSuggestedSkusKeyboard(List<String> skus) {
        List<List<Map<String, String>>> rows = new ArrayList<>();

        for (String sku : skus.stream().distinct().limit(6).toList()) {
            rows.add(List.of(buildBuySkuButton(sku)));
        }

        rows.add(List.of(
                Map.of("text", "📋 Ver Catálogo Completo", "callback_data", "SHOW_CATALOG")
        ));

        return Map.of("inline_keyboard", rows);
    }

    private Map<String, String> buildBuySkuButton(String sku) {
        return Map.of(
                "text", "🛒 Comprar " + sku,
                "callback_data", "BUY_SKU:" + sku
        );
    }

    public boolean setWebhook(String webhookUrl, String secretToken) {
        String url = apiUrl + botToken + "/setWebhook";

        Map<String, Object> payload = new HashMap<>();
        payload.put("url", webhookUrl);
        payload.put("secret_token", secretToken);

        try {
            ResponseEntity<String> response = postToTelegram(url, payload, "setWebhook", null);
            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                log.info("Webhook de Telegram registrado con secret token: {}", webhookUrl);
            }
            return success;
        } catch (Exception ex) {
            log.error("No se pudo registrar el webhook de Telegram en {}: {}", webhookUrl, ex.getMessage(), ex);
            return false;
        }
    }

    private ResponseEntity<String> postToTelegram(
            String url, Map<String, Object> payload, String operation, Long chatId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if ("sendMessage".equals(operation)) {
                log.info("Mensaje enviado a Telegram chatId={}", chatId);
            }
            return response;
        } catch (HttpClientErrorException ex) {
            log.error("Error HTTP en {} chatId={}: status={}, body={}",
                    operation, chatId, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            throw ex;
        } catch (ResourceAccessException ex) {
            log.error("Error de red en {} chatId={}: {}", operation, chatId, ex.getMessage(), ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Error inesperado en {} chatId={}: {}", operation, chatId, ex.getMessage(), ex);
            throw ex;
        }
    }
}
