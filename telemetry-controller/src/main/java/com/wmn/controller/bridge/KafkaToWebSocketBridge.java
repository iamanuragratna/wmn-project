package com.wmn.controller.bridge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wmn.controller.dto.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaToWebSocketBridge {
    private static final Logger log = LoggerFactory.getLogger(KafkaToWebSocketBridge.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.websocket.destination-prefix:/topic/telemetry}")
    private String wsDestination; // e.g. /topic/telemetry

    public KafkaToWebSocketBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        // Defensive mapper configuration
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new JavaTimeModule());
    }

    // Listen to features topic -> publish feature_update
    @KafkaListener(topics = "${kafka.topic.features:wmn.features.v1}", groupId = "controller-ws-bridge",
            containerFactory = "stringKafkaListenerContainerFactory")
    public void onFeature(ConsumerRecord<String, String> rec) {
        handleAndForward(rec,
                FeatureUpdate.class,
                "feature_update",
                "feature_update_raw",
                "feature_update_empty");
    }

    // Listen to forecasts topic -> publish forecast_update
    @KafkaListener(topics = "${kafka.topic.forecasts:wmn.forecasts.v1}", groupId = "controller-ws-bridge",
            containerFactory = "stringKafkaListenerContainerFactory")
    public void onForecast(ConsumerRecord<String, String> rec) {
        handleAndForward(rec,
                ForecastUpdate.class,
                "forecast_update",
                "forecast_update_raw",
                "forecast_update_empty");
    }

    // Listen to optimizer plans -> publish optimizer_plan
    @KafkaListener(topics = "${kafka.topic.plans:wmn.chconfigs.v1}", groupId = "controller-ws-bridge",
            containerFactory = "stringKafkaListenerContainerFactory")
    public void onPlan(ConsumerRecord<String, String> rec) {
        handleAndForward(rec,
                OptimizerPlan.class,
                "optimizer_plan",
                "optimizer_plan_raw",
                "optimizer_plan_empty");
    }

    // Listen to commands status -> publish command_status
    @KafkaListener(topics = "${kafka.topic.commands:wmn.commands.v1}", groupId = "controller-ws-bridge",
            containerFactory = "stringKafkaListenerContainerFactory")
    public void onCommandStatus(ConsumerRecord<String, String> rec) {
        handleAndForward(rec,
                CommandStatus.class,
                "command_status",
                "command_status_raw",
                "command_status_empty");
    }

    /**
     * Generic handler to reduce duplication: logs, maps (if possible) and forwards to websocket.
     *
     * @param rec               ConsumerRecord received from Kafka
     * @param targetClass       DTO class to map to (e.g. ForecastUpdate.class)
     * @param successEventType  WS event type on successful parse (e.g. "forecast_update")
     * @param rawEventType      WS event type when parse fails (sends raw payload)
     * @param emptyEventType    WS event type when payload is null/empty
     */
    private <T> void handleAndForward(ConsumerRecord<String, String> rec,
                                      Class<T> targetClass,
                                      String successEventType,
                                      String rawEventType,
                                      String emptyEventType) {
        try {
            String raw = rec == null ? null : rec.value();
            String key = rec == null ? null : rec.key();
            String topic = rec == null ? "unknown" : rec.topic();
            int partition = rec == null ? -1 : rec.partition();
            long offset = rec == null ? -1L : rec.offset();

           /* log.info("Received record: topic={} partition={} offset={} key={} valueLength={}",
                    topic, partition, offset, key, raw == null ? 0 : raw.length()); */

            if (raw != null) {
                final int PREVIEW_LEN = 400;
                String preview = raw.length() <= PREVIEW_LEN ? raw : raw.substring(0, PREVIEW_LEN) + "...(truncated)";
                log.debug("Raw payload preview for {}: {}", topic, preview);
            } else {
                log.warn("Record has null value (tombstone?) for key={} at {}:{}:{}", key, topic, partition, offset);
            }

            if (raw == null || raw.trim().isEmpty()) {
                EventMessage ev = new EventMessage(emptyEventType, raw);
                messagingTemplate.convertAndSend(wsDestination, ev);
                return;
            }

            try {
                T payload = mapper.readValue(raw, targetClass);
                EventMessage ev = new EventMessage(successEventType, payload);
                messagingTemplate.convertAndSend(wsDestination, ev);
                /*log.info("Parsed {} and forwarded to websocket (topic={} offset={})",
                        targetClass.getSimpleName(), topic, offset); */
            } catch (Exception mapEx) {
                log.error("Failed to parse JSON to {} (topic={} offset={}): {}. Sending raw to websocket.",
                        targetClass.getSimpleName(), topic, offset, mapEx.toString());
                EventMessage rawEv = new EventMessage(rawEventType, raw);
                messagingTemplate.convertAndSend(wsDestination, rawEv);
            }
        } catch (Exception e) {
            log.error("Unexpected exception in handleAndForward for topic: {}",
                    (rec == null ? "unknown" : rec.topic()), e);
            // Do not rethrow — we want listener to continue processing other records
        }
    }
}
