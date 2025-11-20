
package com.wmn.optimizer.dto;

public class ChannelConfig {
    private String nodeId;
    private int channel;
    private String reason;

    public ChannelConfig(){}

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public int getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
