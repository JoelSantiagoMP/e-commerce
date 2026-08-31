package com.tienda.gemini.dto;

import com.tienda.telegram.dto.PendingOrderLine;
import java.util.List;

public record GeminiChatResult(
        String message,
        List<String> suggestedSkus,
        List<PendingOrderLine> pendingOrderLines) {

    public GeminiChatResult(String message, List<String> suggestedSkus) {
        this(message, suggestedSkus, List.of());
    }

    public static GeminiChatResult textOnly(String message) {
        return new GeminiChatResult(message, List.of(), List.of());
    }
}
