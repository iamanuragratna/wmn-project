
package com.wmn.optimizer.dto;

import java.time.OffsetDateTime;

public class Forecast {
    private String nodeId;
    private String timestamp;
    private double forecastBusyPercent;
    private String method;
    private int windowSeconds;

    public Forecast() {}

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public double getForecastBusyPercent() { return forecastBusyPercent; }
    public void setForecastBusyPercent(double forecastBusyPercent) { this.forecastBusyPercent = forecastBusyPercent; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
}
