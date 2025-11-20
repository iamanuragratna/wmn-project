
package com.wmn.optimizer;

import com.wmn.optimizer.dto.ChannelConfig;
import com.wmn.optimizer.dto.Forecast;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class OptimizerService {

    @Autowired
    private KafkaTemplate<String, ChannelConfig> kafkaTemplate;

    @KafkaListener(topics = "${kafka.topic.forecasts}", groupId = "optimizer-service", containerFactory = "jsonListenerFactory")
    public void handleForecast(Forecast f) {
        System.out.println("Received forecast node=" + f.getNodeId() + " busy=" + f.getForecastBusyPercent());
        ChannelConfig cfg = new ChannelConfig();
        cfg.setNodeId(f.getNodeId());
        // simple rule: >75 -> channel 1, 50-75 -> channel 6, else channel 11
        double busy = f.getForecastBusyPercent();
        if (busy > 75) cfg.setChannel(1);
        else if (busy > 50) cfg.setChannel(6);
        else cfg.setChannel(11);
        cfg.setReason("auto-rule:busy=" + busy);
        kafkaTemplate.send("wmn.chconfigs.v1", cfg.getNodeId(), cfg);
        System.out.println("Published ChannelConfig for " + cfg.getNodeId() + " -> ch=" + cfg.getChannel());
    }
}
