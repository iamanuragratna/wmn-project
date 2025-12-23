package com.wmn.controller.dto;

public class ForecastUpdate {
    public String nodeId;
    public String timestamp;
    public double forecastBusyPercent;
    public String method;
    public int windowSeconds;

    private double confidence;
    public long sampleCount;
    public Integer channel;
    public Boolean synthetic;
    public Double avgNumClients;

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

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public Boolean getSynthetic() {
        return synthetic;
    }

    public void setSynthetic(Boolean synthetic) {
        this.synthetic = synthetic;
    }

    public Double getAvgNumClients() {
        return avgNumClients;
    }

    public void setAvgNumClients(Double avgNumClients) {
        this.avgNumClients = avgNumClients;
    }
}
