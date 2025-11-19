package com.wmn.aggregation.service;

import com.wmn.aggregation.model.Feature;
import com.wmn.aggregation.model.Telemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.cloud.stream.function.StreamBridge;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.*;

@Service
public class AggregationService {

    private final ConcurrentMap<String, Deque<Telemetry>> store = new ConcurrentHashMap<>();
    private final StreamBridge streamBridge;

    @Value("${processor.window.seconds:60}")
    private long windowSeconds;

    public AggregationService(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public void addTelemetry(Telemetry t) {
        if (t == null || t.nodeId == null) return;
        store.computeIfAbsent(t.nodeId, k -> new ConcurrentLinkedDeque<>()).addLast(t);
    }

    public void aggregateAndPublish() {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);
        for (Map.Entry<String, Deque<Telemetry>> e : store.entrySet()) {
            String node = e.getKey();
            Deque<Telemetry> dq = e.getValue();

            // drop old entries from head
            while (!dq.isEmpty()) {
                Telemetry first = dq.peekFirst();
                try {
                    Instant ts = Instant.parse(first.timestamp);
                    if (ts.isBefore(windowStart)) dq.pollFirst();
                    else break;
                } catch (Exception ex) {
                    // if parse fails, break to avoid removing
                    break;
                }
            }

            List<Telemetry> windowList = new ArrayList<>(dq);

            if (windowList.isEmpty()) continue;

            Feature f = computeFeature(node, windowList, windowStart, now);
            // publish using StreamBridge to binding 'output-out-0'
            streamBridge.send("output-out-0", MessageBuilder.withPayload(f).build());
            System.out.println("Published feature: " + f);
        }
    }

    private Feature computeFeature(String node, List<Telemetry> list, Instant start, Instant end) {
        Feature f = new Feature();
        f.nodeId = node;
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
        f.avgChannelBusyPercent = f.sampleCount==0?0: sumBusy / f.sampleCount;
        f.maxChannelBusyPercent = maxBusy==Double.NEGATIVE_INFINITY?0:maxBusy;
        f.minRssi = minRssi==Integer.MAX_VALUE?0:minRssi;
        f.avgRssi = f.sampleCount==0?0: sumRssi / f.sampleCount;
        f.sumTxBytes = sumTx;
        f.lastSeen = lastSeen;
        return f;
    }
}
