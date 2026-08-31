package com.tienda.telegram.service;

import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TelegramUpdateDeduplicationService {

    private static final long TTL_MILLIS = 15_000L;

    private final ConcurrentHashMap<Long, Long> processedUpdates = new ConcurrentHashMap<>();

    public boolean isDuplicate(Long updateId) {
        if (updateId == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        evictExpired(now);

        Long previousTimestamp = processedUpdates.putIfAbsent(updateId, now);
        if (previousTimestamp != null) {
            log.info("Update duplicado ignorado. updateId={}", updateId);
            return true;
        }

        return false;
    }

    private void evictExpired(long now) {
        long cutoff = now - TTL_MILLIS;
        processedUpdates.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
}
