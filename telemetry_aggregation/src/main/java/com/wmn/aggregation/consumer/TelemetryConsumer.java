package com.wmn.aggregation.consumer;

import com.wmn.aggregation.model.Telemetry;
import com.wmn.aggregation.service.AggregationService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class TelemetryConsumer {

    private final AggregationService agg;

    public TelemetryConsumer(AggregationService agg) {
        this.agg = agg;
    }

    @Bean
    public Consumer<Telemetry> input() {
        return t -> {
            System.out.println("Received telemetry: " + t);
            agg.addTelemetry(t);
        };
    }
}
