package com.wmn.telemetry.controller;

import com.wmn.telemetry.model.Telemetry;
import com.wmn.telemetry.repository.TelemetryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    @Autowired
    private TelemetryRepository repository;

    @GetMapping("/node/{nodeId}")
    public List<Telemetry> getLatestForNode(@PathVariable String nodeId) {
        return repository.findTop100ByNodeIdOrderByTsDesc(nodeId);
    }

    @GetMapping("/health")
    public String health() { return "OK"; }
}
