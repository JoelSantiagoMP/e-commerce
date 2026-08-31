package com.tienda.gemini.exception;

public class GeminiRateLimitException extends RuntimeException {

    public GeminiRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
