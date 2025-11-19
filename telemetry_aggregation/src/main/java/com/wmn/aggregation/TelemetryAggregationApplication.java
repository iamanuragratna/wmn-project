package com.wmn.aggregation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TelemetryAggregationApplication {
    public static void main(String[] args) {
        SpringApplication.run(TelemetryAggregationApplication.class, args);
    }
}
