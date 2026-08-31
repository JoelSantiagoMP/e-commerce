package com.tienda.telegram.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramUpdateDeduplicationServiceTest {

    private TelegramUpdateDeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        deduplicationService = new TelegramUpdateDeduplicationService();
    }

    @Test
    void isDuplicate_firstUpdateIsNotDuplicate() {
        assertFalse(deduplicationService.isDuplicate(100L));
    }

    @Test
    void isDuplicate_sameUpdateIdWithinTtlIsDuplicate() {
        assertFalse(deduplicationService.isDuplicate(101L));
        assertTrue(deduplicationService.isDuplicate(101L));
    }

    @Test
    void isDuplicate_nullUpdateIdIsNotDuplicate() {
        assertFalse(deduplicationService.isDuplicate(null));
    }
}
