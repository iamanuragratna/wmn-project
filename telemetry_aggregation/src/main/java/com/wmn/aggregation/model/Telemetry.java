package com.wmn.aggregation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Telemetry {
    public String nodeId;
    public String timestamp;
    public String radioId;
    public Integer channel;
    public Integer rssi;
    public Integer snr;
    public Long txBytes;
    public Long rxBytes;
    public Integer txRetries;
    public Integer numClients;
    public Double channelBusyPercent;
    public List<Map<String,Object>> interferenceScan;

    public String toString() {
        return "Telemetry{nodeId="+nodeId+", ts="+timestamp+", rssi="+rssi+", busy="+channelBusyPercent+"}";
    }
}
