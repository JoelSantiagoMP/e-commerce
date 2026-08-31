package com.tienda.gemini.dto;

import java.util.List;

public record GeminiChatResult(String message, List<String> suggestedSkus) {

    public static GeminiChatResult textOnly(String message) {
        return new GeminiChatResult(message, List.of());
    }
}
