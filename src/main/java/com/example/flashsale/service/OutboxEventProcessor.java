package com.example.flashsale.service;

import com.example.flashsale.cache.StockCache;
import com.example.flashsale.domain.OutboxEvent;
import com.example.flashsale.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventProcessor {

    private final OutboxRepository outboxRepository;
    private final StockCache stockCache;
    private final ObjectMapper objectMapper;

    public OutboxEventProcessor(
            OutboxRepository outboxRepository,
            StockCache stockCache,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.stockCache = stockCache;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(Long eventId) throws Exception {
        OutboxEvent event = outboxRepository.findByIdForUpdate(eventId)
                .orElseThrow();

        if (event.getStatus() == OutboxEvent.Status.PROCESSED) {
            return;
        }

        JsonNode payload = objectMapper.readTree(event.getPayload());
        Long productId = payload.get("productId").asLong();

        // Strict: if Redis is down this throws, the transaction rolls back,
        // and the event remains PENDING for the next attempt.
        event.markAttempt();
        stockCache.deleteOrThrow(productId);

        event.markProcessed();
    }
}
