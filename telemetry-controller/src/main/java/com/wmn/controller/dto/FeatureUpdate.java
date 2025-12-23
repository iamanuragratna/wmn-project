package com.wmn.controller.dto;

public class FeatureUpdate {
    public String nodeId;
    public String windowStart;
    public String windowEnd;
    public String granularity;
    public long sampleCount;
    public double avgChannelBusyPercent;
    public double maxChannelBusyPercent;
    public int minRssi;
    public double avgRssi;
    public long sumTxBytes;
    public String lastSeen;
    public Integer channel;

    public Boolean synthetic;
    public Double avgNumClients;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart;
    }

    public String getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(String windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public double getAvgChannelBusyPercent() {
        return avgChannelBusyPercent;
    }

    public void setAvgChannelBusyPercent(double avgChannelBusyPercent) {
        this.avgChannelBusyPercent = avgChannelBusyPercent;
    }

    public double getMaxChannelBusyPercent() {
        return maxChannelBusyPercent;
    }

    public void setMaxChannelBusyPercent(double maxChannelBusyPercent) {
        this.maxChannelBusyPercent = maxChannelBusyPercent;
    }

    public int getMinRssi() {
        return minRssi;
    }

    public void setMinRssi(int minRssi) {
        this.minRssi = minRssi;
    }

    public double getAvgRssi() {
        return avgRssi;
    }

    public void setAvgRssi(double avgRssi) {
        this.avgRssi = avgRssi;
    }

    public long getSumTxBytes() {
        return sumTxBytes;
    }

    public void setSumTxBytes(long sumTxBytes) {
        this.sumTxBytes = sumTxBytes;
    }

    public String getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(String lastSeen) {
        this.lastSeen = lastSeen;
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
