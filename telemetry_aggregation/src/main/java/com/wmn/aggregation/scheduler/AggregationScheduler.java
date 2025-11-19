package com.wmn.aggregation.scheduler;

import com.wmn.aggregation.service.AggregationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AggregationScheduler {

    private final AggregationService service;

    public AggregationScheduler(AggregationService service) {
        this.service = service;
    }

    // run every 15 seconds
    @Scheduled(fixedRateString = "${processor.schedule.ms:15000}")
    public void run() {
        service.aggregateAndPublish();
    }
}
