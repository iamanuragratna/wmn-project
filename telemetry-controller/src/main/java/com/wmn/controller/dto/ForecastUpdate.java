package com.wmn.controller.dto;

public class ForecastUpdate {
    public String nodeId;
    public String timestamp;
    public double forecastBusyPercent;
    public String method;
    public int windowSeconds;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public double getForecastBusyPercent() {
        return forecastBusyPercent;
    }

    public void setForecastBusyPercent(double forecastBusyPercent) {
        this.forecastBusyPercent = forecastBusyPercent;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
