package com.wmn.aggregation.model;

public class Feature {
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

    public String toString() {
        return "Feature[nodeId="+nodeId+",samples="+sampleCount+"]";
    }
}
