package com.wmn.telemetry.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "telemetry")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Telemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nodeId;
    private String radioId;

    @JsonProperty("timestamp")
    @Column(name = "ts")
    private OffsetDateTime ts;

    private Integer channel;
    private Integer rssi;
    private Integer snr;
    private Long txBytes;
    private Long rxBytes;
    private Integer txRetries;
    private Integer numClients;
    private Double channelBusyPercent;

    private String sampleSource;

    // Use AttributeConverter to serialize/deserialize the interferenceScan list.
    // Column type: text (stores JSON string). If you later want jsonb, see SQL below.
    @JsonProperty("interferenceScan")
    @Convert(converter = JsonConverter.class)
    @Column(name = "interference", columnDefinition = "text")
    private List<Map<String, Object>> interference;

    // getters/setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getRadioId() { return radioId; }
    public void setRadioId(String radioId) { this.radioId = radioId; }
    public OffsetDateTime getTs() { return ts; }
    public void setTs(OffsetDateTime ts) { this.ts = ts; }
    public Integer getChannel() { return channel; }
    public void setChannel(Integer channel) { this.channel = channel; }
    public Integer getRssi() { return rssi; }
    public void setRssi(Integer rssi) { this.rssi = rssi; }
    public Integer getSnr() { return snr; }
    public void setSnr(Integer snr) { this.snr = snr; }
    public Long getTxBytes() { return txBytes; }
    public void setTxBytes(Long txBytes) { this.txBytes = txBytes; }
    public Long getRxBytes() { return rxBytes; }
    public void setRxBytes(Long rxBytes) { this.rxBytes = rxBytes; }
    public Integer getTxRetries() { return txRetries; }
    public void setTxRetries(Integer txRetries) { this.txRetries = txRetries; }
    public Integer getNumClients() { return numClients; }
    public void setNumClients(Integer numClients) { this.numClients = numClients; }
    public Double getChannelBusyPercent() { return channelBusyPercent; }
    public void setChannelBusyPercent(Double channelBusyPercent) { this.channelBusyPercent = channelBusyPercent; }
    public List<Map<String, Object>> getInterference() { return interference; }
    public void setInterference(List<Map<String, Object>> interference) { this.interference = interference; }

    public String getSampleSource() {
        return sampleSource;
    }

    public void setSampleSource(String sampleSource) {
        this.sampleSource = sampleSource;
    }
}
