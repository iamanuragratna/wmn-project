package com.wmn.controller.api;

import com.wmn.controller.dto.CommandStatus;
import com.wmn.controller.dto.OptimizerPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AdminController {

    @Autowired
    private final KafkaTemplate<String, Object> kafkaTemplateForWebSocket;

    public AdminController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplateForWebSocket = kafkaTemplate;
    }

    // optional admin: publish optimizer plan
    @PostMapping("/optimizer/plan")
    public String postPlan(@RequestBody OptimizerPlan plan) {
        kafkaTemplateForWebSocket.send("wmn.chconfigs.v1", plan.nodeId, plan);
        return "ok";
    }

    // optional admin: push command
    @PostMapping("/controller/commands")
    public String postCommand(@RequestBody CommandStatus cmd) {
        kafkaTemplateForWebSocket.send("wmn.commands.v1", cmd.nodeId, cmd);
        return "ok";
    }
}
