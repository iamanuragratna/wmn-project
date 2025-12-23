package com.wmn.optimizer;

import com.wmn.optimizer.dto.ChannelConfig;
import com.wmn.optimizer.dto.Forecast;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OptimizerService (improved)
 *
 * - Maintains LatestForecasts per node/channel with confidence & synthetic flag.
 * - Keeps assignedChannel & assignedContribution to maintain channelLoad correctly.
 * - Adds hysteresis/move-cost calculation to avoid small-benefit moves.
 * - Keeps per-node recent-target-history to avoid cycling.
 * - Requires higher effective threshold when candidate data is low-confidence or synthetic.
 * - FIX 1: If assignedChannel is missing, attempt to infer current operating channel from forecasts
 *          (prefer non-synthetic with highest sampleCount, else highest confidence).
 */
@Service
public class OptimizerService {

    @Autowired
    private KafkaTemplate<String, ChannelConfig> kafkaTemplate;

    // Currently assigned channel per node
    private final Map<String, Integer> assignedChannel = new ConcurrentHashMap<>();
    // contribution value used when node was assigned (so we can subtract on reassignment)
    private final Map<String, Double> assignedContribution = new ConcurrentHashMap<>();
    private final Map<String, Long> assignedAtMs = new ConcurrentHashMap<>();

    // channel -> estimated total load (sum of contributions)
    private final Map<Integer, Double> channelLoad = new ConcurrentHashMap<>();

    // confirmation logic per node
    private final Map<String, Integer> confirmCount = new ConcurrentHashMap<>();

    // per-node recent target history to avoid cycling (most recent first)
    private final Map<String, Deque<Integer>> recentTargets = new ConcurrentHashMap<>();
    private final int recentTargetsSize = 5; // remember last 5 attempted targets

    // tuning params (tweak per experiments)
    private int minConfirmations = 3;
    private double improvementThreshold = 0.0; // require >5.0 absolute improvement
    private double lowConfidencePenaltyScale = 0.0; // cost added per (1-confidence)
    private double baseMoveCost = 0.0; // base cost (absolute busy points) for any move
    private double clientPenaltyPerClient = 0.2; // cost per client (reassoc cost)
    private long minTimeBetweenMovesMs = 00_000L; // per-node min time between confirmed assignments (hysteresis)
    private double historyPenalty = 0.0; // additional penalty if channel is in recentTargets

    // simple in-memory LatestForecasts store
    static class ForecastEntry {
        public final double forecast;
        public final double confidence;
        public final boolean synthetic;
        public final long lastUpdatedMs;
        public final long sampleCount;
        public final double avgNumClients;
        public ForecastEntry(double forecast, double confidence, boolean synthetic, long lastUpdatedMs, long sampleCount, double avgNumClients) {
            this.forecast = forecast;
            this.confidence = confidence;
            this.synthetic = synthetic;
            this.lastUpdatedMs = lastUpdatedMs;
            this.sampleCount = sampleCount;
            this.avgNumClients = avgNumClients;
        }
    }

    // node -> (channel -> ForecastEntry)
    private static final Map<String, Map<Integer, ForecastEntry>> latestForecasts = new ConcurrentHashMap<>();

    public static void updateLatestForecast(String node, int channel, ForecastEntry entry) {
        latestForecasts.computeIfAbsent(node, n -> new ConcurrentHashMap<>()).put(channel, entry);
    }

    public static Map<Integer, ForecastEntry> getForNode(String node) {
        return latestForecasts.getOrDefault(node, Collections.emptyMap());
    }

    @KafkaListener(topics = "${kafka.topic.forecasts}", groupId = "optimizer-service", containerFactory = "jsonListenerFactory")
    public void handleForecast(Forecast f) {
        try {
            String node = f.getNodeId();
            Integer ch = f.getChannel();
            if (node == null || ch == null) return;

            double forecastBusy = f.getForecastBusyPercent();
            double confidence = f.getConfidence();
            boolean synthetic = f.getSynthetic();
            long sampleCount =  f.getSampleCount();
            double avgNumClients = f.getAvgNumClients() == null ? 0.0 : f.getAvgNumClients();

            // update latest forecasts map
            ForecastEntry entry = new ForecastEntry(forecastBusy, confidence, synthetic, Instant.now().toEpochMilli(), sampleCount, avgNumClients);
            updateLatestForecast(node, ch, entry);

            // Decide best channel for node
            Map<Integer, ForecastEntry> candidates = getForNode(node);
            if (candidates.isEmpty()) return;

            // compute costs for candidates and pick best
            double bestCost = Double.POSITIVE_INFINITY;
            int bestChannel = -1;

            for (Map.Entry<Integer, ForecastEntry> e : candidates.entrySet()) {
                int candidateCh = e.getKey();
                ForecastEntry fe = e.getValue();
                double predicted = fe.forecast;
                double load = channelLoad.getOrDefault(candidateCh, 0.0);
                double confidencePenalty = (1.0 - fe.confidence) * lowConfidencePenaltyScale;
                double cost = predicted + load * 0.5 + confidencePenalty;

                // history penalty: if candidate channel recently tried for this node, discourage
                Deque<Integer> hist = recentTargets.get(node);
                if (hist != null && hist.contains(candidateCh)) {
                    cost += historyPenalty;
                }

                if (cost < bestCost) {
                    bestCost = cost;
                    bestChannel = candidateCh;
                }
            }

            // --- FIX 1: infer currentAssigned if missing ---
            int currentAssigned = assignedChannel.getOrDefault(node, -1);
            boolean inferred = false;
            if (currentAssigned == -1) {
                // prefer non-synthetic entries with largest sampleCount
                Optional<Map.Entry<Integer, ForecastEntry>> maybe = candidates.entrySet().stream()
                        .filter(e -> !e.getValue().synthetic && e.getValue().sampleCount > 0)
                        .max(Comparator.comparingLong(e -> e.getValue().sampleCount));
                if (maybe.isPresent()) {
                    currentAssigned = maybe.get().getKey();
                    inferred = true;
                } else {
                    // fallback: highest-confidence candidate
                    Optional<Map.Entry<Integer, ForecastEntry>> maybeConf = candidates.entrySet().stream()
                            .max(Comparator.comparingDouble(e -> e.getValue().confidence));
                    if (maybeConf.isPresent()) {
                        currentAssigned = maybeConf.get().getKey();
                        inferred = true;
                    }
                }
            }
            // -------------------------------------------------

            // compute currentCost for the currently assigned channel (if any)
            double currentCost;
            if (currentAssigned != -1) {
                ForecastEntry curEntry = candidates.get(currentAssigned);
                double curPred = curEntry == null ? 100.0 : curEntry.forecast;
                double curLoad = channelLoad.getOrDefault(currentAssigned, 0.0);
                double curPenalty = curEntry == null ? lowConfidencePenaltyScale : (1.0 - curEntry.confidence) * lowConfidencePenaltyScale;
                currentCost = curPred + curLoad * 0.5 + curPenalty;
                // if we inferred the assigned channel and that inference is low-confidence, add a small buffer
                if (inferred && curEntry != null && curEntry.confidence < 0.3) {
                    currentCost += 5.0;
                }
            } else {
                // no info — avoid infinite improvement by setting currentCost slightly above bestCost
                currentCost = bestCost + baseMoveCost;
            }

            // compute raw improvement
            double improvement = currentCost - bestCost;

            // require that bestChannel has enough confidence / reality to act
            ForecastEntry bestEntry = candidates.get(bestChannel);
            if (bestEntry == null) {
                confirmCount.put(node, 0);
                return;
            }
            // If bestEntry is synthetic or low confidence, require stronger evidence or a real sample
            boolean allowBasedOnSynthetic = false;
            if (!bestEntry.synthetic && bestEntry.confidence >= 0.3) allowBasedOnSynthetic = true; // realish
            if (bestEntry.synthetic && bestEntry.confidence >= 0.75) allowBasedOnSynthetic = true; // very confident synthetic (rare)
            if (!allowBasedOnSynthetic) {
                // check if there exists any real sample for this candidate channel recently (sampleCount > 0 and synthetic==false)
                if (bestEntry.synthetic) {
                    // require at least one non-synthetic forecast for this node+channel before moving
                    boolean haveReal = false;
                    if (candidates.containsKey(bestChannel)) {
                        var best = candidates.get(bestChannel);
                        haveReal = !best.synthetic && best.sampleCount > 0;
                    }
                    if (!haveReal) {
                        // don't act yet; reset confirmations and wait for better data
                        confirmCount.put(node, 0);
                        System.out.println("Optimizer: skip move for " + node + " -> ch=" + bestChannel + " (only synthetic/low confidence)");
                        return;
                    }
                } else if (bestEntry.confidence < 0.25) {
                    confirmCount.put(node, 0);
                    System.out.println("Optimizer: skip move for " + node + " -> ch=" + bestChannel + " (low confidence " + bestEntry.confidence + ")");
                    return;
                }
            }

            // Hysteresis / move cost calculation
            // estimate number of clients from latest forecasts info if available (fallback 0)
            // NOTE: ideally we would have avg numClients in Feature; if not, use 0
            double estimatedClients = f.getAvgNumClients() == null ? 0.0 : f.getAvgNumClients();

            double moveCost = baseMoveCost + clientPenaltyPerClient * estimatedClients;
            // if candidate channel is in recentTargets, add extra penalty
            Deque<Integer> hist = recentTargets.get(node);
            if (hist != null && hist.contains(bestChannel)) {
                moveCost += historyPenalty;
            }

            double effectiveImprovement = improvement - moveCost;

            // if all candidate confidences are low, require larger improvement
            boolean allLowConfidence = candidates.values().stream().allMatch(en -> en.confidence < 0.5);
            double effectiveThreshold = improvementThreshold * (allLowConfidence ? 2.0 : 1.0);

            if (effectiveImprovement < effectiveThreshold) {
                confirmCount.put(node, 0);
                System.out.println("Optimizer: insufficient net improvement node=" + node + " bestCh=" + bestChannel
                        + " netImprovement=" + effectiveImprovement + " threshold=" + effectiveThreshold);
                return;
            }

            // check min time since last assignment to avoid rapid reassignment
            Long lastAssignedAt = assignedAtMs.get(node);
            long nowMs = Instant.now().toEpochMilli();
            if (lastAssignedAt != null && (nowMs - lastAssignedAt) < minTimeBetweenMovesMs) {
                System.out.println("Optimizer: recent assignment too fresh for node=" + node + " skipping");
                confirmCount.put(node, 0);
                return;
            }

            // confirmations
            int c = confirmCount.getOrDefault(node, 0) + 1;
            confirmCount.put(node, c);
            if (c < minConfirmations) {
                System.out.println("Optimizer: waiting confirmations for node=" + node + " bestCh=" + bestChannel + " cnt=" + c);
                return;
            }

            // commit assignment:
            double bestPredicted = bestEntry.forecast;
            int previousAssigned = assignedChannel.getOrDefault(node, -1);
            double previousContribution = assignedContribution.getOrDefault(node, 0.0);

            // subtract previous contribution
            if (previousAssigned != -1) {
                channelLoad.put(previousAssigned, channelLoad.getOrDefault(previousAssigned, 0.0) - previousContribution);
                if (channelLoad.get(previousAssigned) < 1e-6) channelLoad.put(previousAssigned, 0.0);
            }

            // add new contribution
            channelLoad.put(bestChannel, channelLoad.getOrDefault(bestChannel, 0.0) + bestPredicted);

            // persist assignment
            assignedChannel.put(node, bestChannel);
            assignedContribution.put(node, bestPredicted);
            assignedAtMs.put(node, nowMs);
            confirmCount.put(node, 0);

            // update recent targets
            recentTargets.computeIfAbsent(node, n -> {
                Deque<Integer> dq = new ArrayDeque<>();
                return dq;
            });
            Deque<Integer> dq = recentTargets.get(node);
            dq.addFirst(bestChannel);
            while (dq.size() > recentTargetsSize) dq.removeLast();

            // publish ChannelConfig
            ChannelConfig cfg = new ChannelConfig();
            cfg.setNodeId(node);
            cfg.setChannel(bestChannel);
            cfg.setReason("optimizer:netImp=" + String.format("%.2f", effectiveImprovement) + ",rawImp=" + String.format("%.2f", improvement));
            kafkaTemplate.send("wmn.chconfigs.v1", cfg.getNodeId(), cfg);
            System.out.println("Published ChannelConfig for " + cfg.getNodeId() + " -> ch=" + cfg.getChannel() + " netImp="
                    + String.format("%.2f", effectiveImprovement));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
