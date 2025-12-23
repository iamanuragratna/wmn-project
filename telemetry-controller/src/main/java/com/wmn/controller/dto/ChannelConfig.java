
package com.wmn.controller.dto;

public class ChannelConfig {
    private String nodeId;
    private Integer channel;
    private String reason;

    public ChannelConfig(){}

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Integer  getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
