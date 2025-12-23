package com.wmn.aggregation.service;

import com.wmn.aggregation.model.Feature;
import com.wmn.aggregation.model.Telemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.cloud.stream.function.StreamBridge;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.*;

/**
 * AggregationService: prefers interferenceScan[*].busy when available.
 *
 * Produces per-node-per-channel Feature objects. If no real samples exist
 * for node+channel in the window, synthesizes a single Telemetry-like sample
 * using:
 *   1) interferenceScan[*].busy (preferred, ground-truth if simulator provides it)
 *   2) else interferenceScan[*].rssi -> rssiToEstimatedBusy heuristic (fallback)
 *
 * Synthesized samples carry sampleSource="scan" and synthFlag=true so downstream
 * services can identify and (optionally) down-weight them.
 */
@Service
public class AggregationService {

    // nodeId -> (channel -> deque)
    private final ConcurrentMap<String, ConcurrentMap<Integer, Deque<Telemetry>>> store = new ConcurrentHashMap<>();
    private final StreamBridge streamBridge;

    @Value("${processor.window.seconds:60}")
    private long windowSeconds;

    // safety: max samples per node-per-channel
    @Value("${processor.max.samples.per.channel:300}")
    private int maxSamplesPerChannel;

    // CSV list of channels from config (default "1,6,11")
    @Value("${simulation.channels:1,6,11}")
    private String channelsCsv;

    // parsed channel list
    private List<Integer> channelList = List.of(1,6,11);

    // Optional toggle to enable/disable scan-based synthesis
    @Value("${processor.synthesize.scans:true}")
    private boolean synthesizeScans = true;

    // Keep the latest interference scan per node along with the timestamp
    private static class ScanRecord {
        public final List<Map<String,Object>> scan;
        public final Instant ts;
        public ScanRecord(List<Map<String,Object>> scan, Instant ts) {
            this.scan = scan;
            this.ts = ts;
        }
    }

    // nodeId -> ScanRecord
    private final ConcurrentMap<String, ScanRecord> latestScans = new ConcurrentHashMap<>();

    public AggregationService(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @PostConstruct
    private void init() {
        try {
            String[] parts = channelsCsv.split(",");
            List<Integer> parsed = new ArrayList<>();
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) parsed.add(Integer.parseInt(s));
            }
            if (!parsed.isEmpty()) channelList = Collections.unmodifiableList(parsed);
        } catch (Exception ex) {
            System.err.println("[agg] failed to parse simulation.channels, using default " + channelList);
        }
    }

    /**
     * Ingest telemetry sample into in-memory store.
     * Also record the latest interferenceScan for the node (if present) to synthesize samples later.
     */
    public void addTelemetry(Telemetry t) {
        if (t == null || t.nodeId == null || t.channel == null) return;
        store.computeIfAbsent(t.nodeId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(t.channel, ch -> new ConcurrentLinkedDeque<>())
                .addLast(t);

        // bound per-channel deque size
        Deque<Telemetry> dq = store.get(t.nodeId).get(t.channel);
        while (dq.size() > maxSamplesPerChannel) dq.pollFirst();

        // record latest interferenceScan (if present) and capture its timestamp
        if (t.interferenceScan != null && t.interferenceScan instanceof List) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> scan = (List<Map<String, Object>>) t.interferenceScan;
                // Use telemetry timestamp if available; else fallback to now
                Instant scanTs;
                try {
                    scanTs = (t.timestamp != null) ? Instant.parse(t.timestamp) : Instant.now();
                } catch (Exception ex) {
                    scanTs = Instant.now();
                }
                latestScans.put(t.nodeId, new ScanRecord(scan, scanTs));
            } catch (Exception ex) {
                // ignore if shape unexpected
            }
        }
    }

    /**
     * Aggregate windows and publish per-node-per-channel Feature objects.
     * Uses real samples where available; otherwise synthesizes one sample using:
     *   - interferenceScan[*].busy (preferred)
     *   - interferenceScan[*].rssi -> heuristic (fallback)
     */
    public void aggregateAndPublish() {
        System.out.println("[agg] aggregateAndPublish started");

        if (store.isEmpty() && latestScans.isEmpty()) {
            System.out.println("[agg] nothing to aggregate");
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);

        // iterate nodes known from store and from scans
        Set<String> nodes = new HashSet<>();
        nodes.addAll(store.keySet());
        nodes.addAll(latestScans.keySet()); // also include nodes that only have scans

        for (String node : nodes) {
            ConcurrentMap<Integer, Deque<Telemetry>> byChannel = store.getOrDefault(node, new ConcurrentHashMap<>());

            for (Integer channel : channelList) {
                Deque<Telemetry> dq = byChannel.getOrDefault(channel, new ConcurrentLinkedDeque<>());

                // prune old entries by timestamp (head -> oldest)
                while (!dq.isEmpty()) {
                    Telemetry first = dq.peekFirst();
                    try {
                        Instant ts = Instant.parse(first.timestamp);
                        if (ts.isBefore(windowStart)) dq.pollFirst();
                        else break;
                    } catch (Exception ex) {
                        // timestamp parse failed — keep sample and avoid deleting incorrectly
                        break;
                    }
                }

                List<Telemetry> windowList = new ArrayList<>(dq);

                boolean hasRecentReal = false;
                if (!dq.isEmpty()) {
                    try {
                        Telemetry last = dq.peekLast();
                        Instant ts = Instant.parse(last.timestamp);
                        if (!ts.isBefore(windowStart)) hasRecentReal = true;
                    } catch (Exception ignored) {}
                }

                if (!hasRecentReal && synthesizeScans) {
                    // synthesize using latestScans (prefer busy if present) but only if scan is recent
                    ScanRecord sr = latestScans.get(node);
                    if (sr != null) {
                        // only synthesize if the scan is recent relative to the window start
                        if (!sr.ts.isBefore(windowStart)) {
                            List<Map<String, Object>> scans = sr.scan;
                            Integer rssiForChannel = null;
                            Double busyForChannel = null;
                            for (Map<String, Object> m : scans) {
                                try {
                                    Object chObj = m.get("channel");
                                    if (chObj == null) continue;
                                    int chVal = Integer.parseInt(chObj.toString());
                                    if (chVal != channel) continue;
                                    Object busyObj = m.get("busy");
                                    if (busyObj != null) {
                                        busyForChannel = Double.parseDouble(busyObj.toString());
                                    }
                                    Object rObj = m.get("rssi");
                                    if (rObj != null) {
                                        rssiForChannel = Integer.parseInt(rObj.toString());
                                    }
                                    break;
                                } catch (Exception ignored) {}
                            }
                            if (busyForChannel != null || rssiForChannel != null) {
                                Telemetry synth = new Telemetry();
                                synth.nodeId = node;
                                synth.timestamp = now.toString();
                                synth.radioId = "scan";
                                synth.channel = channel;
                                // prefer busy value if present
                                if (busyForChannel != null) {
                                    synth.channelBusyPercent = Math.round(busyForChannel * 100.0) / 100.0;
                                } else {
                                    // fallback: map rssi -> estimated busy
                                    int rssi = (rssiForChannel == null) ? -95 : rssiForChannel;
                                    synth.channelBusyPercent = Math.round(rssiToEstimatedBusy(rssi) * 100.0) / 100.0;
                                    synth.rssi = rssi;
                                }
                                // mark synthesized sample
                                synth.sampleSource = "scan";
                                synth.txBytes = 0L;
                                synth.rxBytes = 0L;
                                synth.txRetries = -1;
                                windowList.add(synth);
                            }
                        } else {
                            // scan is stale — remove it to avoid repeated synthesis later
                            latestScans.remove(node, sr);
                        }
                    }
                }

                if (windowList.isEmpty()) {
                    // no data at all for this node+channel
                    System.out.println("[agg] No samples for node=" + node + " ch=" + channel);
                    continue;
                }

                Feature f = computeFeature(node, channel, windowList, windowStart, now);
                // mark feature as synthesized if all samples are synthetic (optional)
                boolean anyReal = windowList.stream().anyMatch(t -> !"scan".equals(t.sampleSource));
                if (!anyReal) {
                    // add an indicator field in feature so downstream can detect pure-scan features
                    f.synthetic = true;
                } else {
                    f.synthetic = false;
                }

                streamBridge.send("output-out-0", MessageBuilder.withPayload(f).build());
                System.out.println("[agg] Published feature (node=" + node + " ch=" + channel + "): " + f);
            }
        }
    }

    /**
     * Simple linear fallback from RSSI to busy% (used only if busy not provided)
     */
    private double rssiToEstimatedBusy(int rssi) {
        final int MIN_RSSI = -95;
        final int MAX_RSSI = -40;
        int clamped = Math.max(MIN_RSSI, Math.min(MAX_RSSI, rssi));
        double normalized = (clamped - MIN_RSSI) / (double) (MAX_RSSI - MIN_RSSI); // 0..1
        double busy = normalized * 100.0;
        if (busy < 0.0) busy = 0.0;
        if (busy > 100.0) busy = 100.0;
        return busy;
    }

    private Feature computeFeature(String node, Integer channel, List<Telemetry> list, Instant start, Instant end) {
        Feature f = new Feature();
        f.nodeId = node;
        f.channel = channel;
        f.windowStart = start.toString();
        f.windowEnd = end.toString();
        f.granularity = windowSeconds + "s";
        f.sampleCount = list.size();

        double sumBusy = 0;
        double maxBusy = Double.NEGATIVE_INFINITY;
        int minRssi = Integer.MAX_VALUE;
        double sumRssi = 0;
        long sumTx = 0;
        String lastSeen = null;

        for (Telemetry t : list) {
            double busy = t.channelBusyPercent == null ? 0.0 : t.channelBusyPercent;
            sumBusy += busy;
            maxBusy = Math.max(maxBusy, busy);
            minRssi = Math.min(minRssi, t.rssi == null ? minRssi : t.rssi);
            sumRssi += t.rssi == null ? 0 : t.rssi;
            sumTx += t.txBytes == null ? 0 : t.txBytes;
            lastSeen = t.timestamp;
        }
        double sumClients = 0;
        int clientsCounted = 0;
        for (Telemetry t : list) {
            if (t.numClients != null) {
                sumClients += t.numClients;
                clientsCounted++;
            }
        }
        if (clientsCounted > 0) {
            f.avgNumClients = sumClients / clientsCounted;
        } else {
            f.avgNumClients = 0.0; // optional: set to 0.0 if you prefer
        }
        f.avgChannelBusyPercent = f.sampleCount == 0 ? 0.0 : sumBusy / f.sampleCount;
        f.maxChannelBusyPercent = maxBusy == Double.NEGATIVE_INFINITY ? 0.0 : maxBusy;
        f.minRssi = minRssi == Integer.MAX_VALUE ? 0 : minRssi;
        f.avgRssi = f.sampleCount == 0 ? 0.0 : sumRssi / f.sampleCount;
        f.sumTxBytes = sumTx;
        f.lastSeen = lastSeen;
        return f;
    }
}
