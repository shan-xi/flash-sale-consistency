package com.example.flashsale.service;

import com.example.flashsale.domain.OutboxEvent;
import com.example.flashsale.repository.OutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxWorker {

    private final OutboxRepository outboxRepository;
    private final OutboxEventProcessor processor;

    public OutboxWorker(
            OutboxRepository outboxRepository,
            OutboxEventProcessor processor
    ) {
        this.outboxRepository = outboxRepository;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms}")
    public void process() {
        List<OutboxEvent> events = outboxRepository.findBatchByStatus(
                OutboxEvent.Status.PENDING,
                PageRequest.of(0, 50)
        );

        for (OutboxEvent event : events) {
            try {
                processor.process(event.getId());
            } catch (Exception ignored) {
                // Redis can be unavailable. The event stays PENDING and is retried.
            }
        }
    }
}
