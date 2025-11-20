
package com.wmn.controller;

import com.wmn.controller.dto.ChannelConfig;
import com.wmn.controller.dto.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ControllerService {

    @Autowired
    private KafkaTemplate<String, Command> kafkaTemplate;

    @KafkaListener(topics = "${kafka.topic.chconfigs}", groupId = "controller-service", containerFactory = "jsonListenerFactory")
    public void handleChannelConfig(ChannelConfig cfg) {
        System.out.println("Received ChannelConfig for node=" + cfg.getNodeId() + " setChannel=" + cfg.getChannel());
        Command cmd = new Command();
        cmd.setNodeId(cfg.getNodeId());
        cmd.setCommand("SET_CHANNEL");
        cmd.setPayload(String.valueOf(cfg.getChannel()));
        kafkaTemplate.send("wmn.commands.v1", cmd.getNodeId(), cmd);
        System.out.println("Published command for " + cmd.getNodeId() + " -> " + cmd.getPayload());
    }
}
