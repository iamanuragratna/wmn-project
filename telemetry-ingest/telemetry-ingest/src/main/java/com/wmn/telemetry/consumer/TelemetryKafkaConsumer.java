package com.wmn.telemetry.consumer;

import com.wmn.telemetry.model.Telemetry;
import com.wmn.telemetry.repository.TelemetryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TelemetryKafkaConsumer {

    @Autowired
    private TelemetryRepository repository;

    @KafkaListener(topics = "${kafka.topic.telemetry}", groupId = "telemetry-ingest-group", containerFactory = "kafkaListenerContainerFactory")
    public void listen(Telemetry telemetry) {
        try {
            repository.save(telemetry);
            System.out.println("Saved telemetry: " + telemetry.getNodeId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
