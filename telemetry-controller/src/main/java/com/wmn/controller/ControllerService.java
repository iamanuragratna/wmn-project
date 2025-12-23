package com.wmn.controller;

import com.wmn.controller.dto.ChannelConfig;
import com.wmn.controller.dto.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class ControllerService {

    @Autowired
    private KafkaTemplate<String, Command> kafkaTemplate;

    // cache node -> last sent channel + timestamp
    private final Map<String, Integer> lastSentChannel = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSentAtMs = new ConcurrentHashMap<>();

    // cooldown map to suppress any change (not just duplicate same-channel)
    private final Map<String, Long> lastChangeAtMs = new ConcurrentHashMap<>();
    private final long holdMs = 30_000L; // existing dedupe window for identical channel
    private final long changeCooldownMs = 60_000L; // suppress any new change within 60s

    @KafkaListener(topics = "${kafka.topic.chconfigs}", groupId = "controller-service", containerFactory = "jsonListenerFactory")
    public void handleChannelConfig(ChannelConfig cfg) {
        System.out.println("Received ChannelConfig for node=" + cfg.getNodeId() + " setChannel=" + cfg.getChannel());

        if (cfg.getNodeId() == null || cfg.getChannel() == null) return;

        String node = cfg.getNodeId();
        int desired = cfg.getChannel();
        long now = Instant.now().toEpochMilli();

        // global cooldown: suppress any new change for some time after a change
        Long lastChange = lastChangeAtMs.get(node);
        if (lastChange != null && (now - lastChange) < changeCooldownMs) {
            System.out.println("Controller: suppressing config for " + node + " -> " + desired + " due to cooldown");
            return;
        }

        // suppress duplicate identical channel within holdMs (existing behaviour)
        Integer last = lastSentChannel.get(node);
        Long lastAt = lastSentAtMs.get(node);
        if (last != null && last == desired && lastAt != null && (now - lastAt) < holdMs) {
            System.out.println("Suppressing duplicate config for " + node + " -> " + desired);
            return;
        }

        // build command with version info
        Command cmd = new Command();
        cmd.setNodeId(node);
        cmd.setCommand("SET_CHANNEL");
        cmd.setPayload(String.valueOf(desired));
        cmd.setConfigVersion("v1:" + Instant.now().toString()); // simple version stamp

        kafkaTemplate.send("wmn.commands.v1", cmd.getNodeId(), cmd);
        lastSentChannel.put(node, desired);
        lastSentAtMs.put(node, now);
        lastChangeAtMs.put(node, now);

        System.out.println("Published command for " + cmd.getNodeId() + " -> " + cmd.getPayload() + " version=" + cmd.getConfigVersion());
    }
}
