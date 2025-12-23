#!/usr/bin/env python3
"""
Enhanced Node Simulator for WMN telemetry (correlated, meaningful telemetry)

Features:
- modes: baseline, interference, burst, switching
- deterministic seed support for repeatability
- optional built-in consumer to listen to wmn.commands.v1 and apply commands (simulate node applying channel change)
- publishes telemetry to wmn.telemetry.v1
- when applying commands it publishes a command_status message back to wmn.commands.v1 (so controller UI sees ack)
- per-node internal state (current channel, last seen, last rssi)
- spatial neighbors and physically-plausible RSSI
- interferenceScan values derived from neighbor transmissions (not purely random)
- operating-channel channelBusyPercent derived from recent tx contributions (smoothed)
- per-channel tx decay so busy map reflects recent activity
- adjustable apply-delay and apply-fail-rate for SET_CHANNEL commands
"""
import argparse
import json
import math
import random
import threading
import time
from datetime import datetime, timezone
from typing import Dict, Any, List, Set, Tuple, Optional
from kafka import KafkaProducer, KafkaConsumer

# Default configuration
KAFKA_BOOTSTRAP = "localhost:9092"
TELEMETRY_TOPIC = "wmn.telemetry.v1"
COMMAND_TOPIC = "wmn.commands.v1"           # listens here for commands
COMMAND_STATUS_TOPIC = "wmn.commands.v1"    # ack published to same topic
DEFAULT_CHANNELS = [1, 6, 11]

# Telemetry generation parameters
DEFAULT_NODES = 10
DEFAULT_INTERVAL = 3.0  # seconds per full cycle
LINGER_MS = 5
ACKS = 1

# Physical / simulation tuneables
DEFAULT_AREA_SIZE = 200.0  # meters (square area side length)
DEFAULT_NEIGHBOR_RADIUS = 50.0  # meters for being a "nearby" node
PATHLOSS_EXPONENT = 3.0
REF_RSSI_AT_1M = -40  # dBm approximate close-in RSSI baseline
NOISE_FLOOR = -95

# Busy smoothing & tx decay
BUSY_SMOOTHING_ALPHA = 0.4   # exponential smoothing for per-node operating-channel busy
PER_CHANNEL_TX_DECAY = 0.6   # multiply per_channel_tx by this every cycle to decay old activity

# Helper: iso timestamp now
def now_iso():
    return datetime.now(timezone.utc).isoformat()

def clamp(v, lo, hi):
    return max(lo, min(hi, v))

class NodeState:
    def __init__(self, node_id: str, channel: int, pos: Tuple[float,float]):
        self.node_id = node_id
        self.channel = channel
        self.pos = pos  # (x,y)
        self.rssi = random.randint(-85, -40)
        self.snr = random.randint(5, 40)
        self.num_clients = max(0, int(random.gauss(5, 3)))
        self.txBytes = 0
        self.rxBytes = 0
        self.last_seen = now_iso()
        # per-channel recent tx activity used for busy computation
        self.per_channel_tx: Dict[int, float] = {}
        # smoothed operating-channel busy percent (for smoother telemetry)
        self.smoothed_busy: float = 0.0

    def to_telemetry(self, channels_list: List[int], local_operating_busy: float,
                     interference_scan: List[Dict[str, Any]], burst_factor: float = 1.0) -> Dict[str, Any]:
        """
        Build telemetry JSON using node internal state and provided interference/operating busy info.
        local_operating_busy should already be computed from per_channel_tx/global map (0..100).
        interference_scan is the list built per node by simulator (see _build_interference_scan_for_node).
        """
        # simulate client and radio dynamics (small jitter)
        self.rssi = max(NOISE_FLOOR, min(-30, self.rssi + random.randint(-2, 2)))
        self.snr = max(0, min(60, self.snr + random.randint(-2, 2)))
        self.num_clients = max(0, self.num_clients + random.randint(-1, 1))

        base_tx = max(0, int(random.gauss(50000, 50000)))
        tx = int(base_tx * burst_factor)
        rx = int(max(0, random.gauss(50000, 50000)) * burst_factor)
        self.txBytes = tx
        self.rxBytes = rx

        # record tx contribution to current channel
        self.per_channel_tx[self.channel] = self.per_channel_tx.get(self.channel, 0.0) + tx

        # smooth the operating busy to avoid jumpy telemetry
        self.smoothed_busy = BUSY_SMOOTHING_ALPHA * local_operating_busy + (1.0 - BUSY_SMOOTHING_ALPHA) * self.smoothed_busy
        busy = clamp(round(self.smoothed_busy, 2), 0.0, 100.0)

        self.last_seen = now_iso()
        telemetry = {
            "nodeId": self.node_id,
            "timestamp": self.last_seen,
            "radioId": "wlan0",
            "channel": self.channel,
            "rssi": self.rssi,
            "snr": self.snr,
            "txBytes": tx,
            "rxBytes": rx,
            "txRetries": random.randint(0, 200),
            "numClients": self.num_clients,
            "channelBusyPercent": busy,
            # interferenceScan provided separately (so we avoid double-randomization)
            "sampleSource": "real"
        }
        # attach scan externally by caller
        telemetry["interferenceScan"] = interference_scan
        return telemetry

class NodeSimulator:
    def __init__(
        self,
        bootstrap: str,
        nodes: int,
        interval: float,
        channels: List[int],
        mode: str = "baseline",
        interference_pct: float = 0.2,
        interference_boost: float = 30.0,
        seed: Optional[int] = None,
        burst_pct: float = 0.0,
        burst_factor: float = 3.0,
        apply_commands: bool = False,
        force_apply: bool = False,
        apply_delay: float = 0.0,
        apply_fail_rate: float = 0.0,
        neighbor_radius: float = DEFAULT_NEIGHBOR_RADIUS,
        area_size: float = DEFAULT_AREA_SIZE
    ):
        if seed is not None:
            random.seed(seed)

        self.bootstrap = bootstrap
        self.nodes = nodes
        self.interval = interval
        self.channels = channels
        self.mode = mode
        self.interference_pct = interference_pct
        self.interference_boost = interference_boost
        self.burst_pct = burst_pct
        self.burst_factor = burst_factor
        self.apply_commands = apply_commands
        self.force_apply = force_apply
        self.apply_delay = apply_delay
        self.apply_fail_rate = apply_fail_rate
        self.neighbor_radius = neighbor_radius
        self.area_size = area_size

        # initial nodes states with random positions
        self.node_states: Dict[int, NodeState] = {}
        for n in range(1, nodes + 1):
            ch = random.choice(self.channels)
            pos = (random.uniform(0, area_size), random.uniform(0, area_size))
            self.node_states[n] = NodeState(f"node-{n:03d}", ch, pos)

        # choose interference nodes and burst nodes sets deterministically with seed
        k_interf = max(1, int(self.interference_pct * nodes))
        self.interf_nodes: Set[int] = set(random.sample(range(1, nodes + 1), k_interf))
        k_burst = max(0, int(self.burst_pct * nodes))
        self.burst_nodes: Set[int] = set(random.sample(range(1, nodes + 1), k_burst))

        # build neighbor lists based on proximity (reproducible per-run)
        self.neighbors: Dict[int, List[int]] = self._compute_neighbors()

        # producer
        self.producer = KafkaProducer(
            bootstrap_servers=self.bootstrap,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            linger_ms=LINGER_MS,
            acks=ACKS,
        )

        self.stop_event = threading.Event()
        self.command_consumer_thread = None
        self.pending_apply_timers: Dict[str, threading.Timer] = {}

    def _compute_neighbors(self) -> Dict[int, List[int]]:
        nb = {}
        for i in range(1, self.nodes + 1):
            x1,y1 = self.node_states[i].pos
            lst = []
            for j in range(1, self.nodes + 1):
                if i == j: continue
                x2,y2 = self.node_states[j].pos
                d = math.hypot(x1-x2, y1-y2)
                if d <= self.neighbor_radius:
                    lst.append(j)
            nb[i] = lst
        return nb

    def start_command_consumer(self):
        if not self.apply_commands:
            return

        def apply_command_delayed(node_idx: int, new_ch: int, nodeId: str, timer_key: str):
            # Simulate possible failure
            if random.random() < self.apply_fail_rate:
                print(f"[sim] command apply FAILED for {nodeId} -> channel={new_ch} (simulated failure)")
                status_msg = {
                    "nodeId": nodeId,
                    "command": "SET_CHANNEL",
                    "payload": str(new_ch),
                    "status": "FAILED",
                    "timestamp": now_iso(),
                    "configVersion": "v1:" + now_iso()
                }
                try:
                    self.producer.send(COMMAND_STATUS_TOPIC, value=status_msg)
                except Exception:
                    pass
                self.pending_apply_timers.pop(timer_key, None)
                return

            # apply the change
            self.node_states[node_idx].channel = new_ch
            # clear previous per-channel tx activity to reflect new channel start
            self.node_states[node_idx].per_channel_tx = {}
            status_msg = {
                "nodeId": nodeId,
                "command": "SET_CHANNEL",
                "payload": str(new_ch),
                "status": "APPLIED",
                "timestamp": now_iso(),
                "configVersion": "v1:" + now_iso()
            }
            try:
                self.producer.send(COMMAND_STATUS_TOPIC, value=status_msg)
                print(f"[sim] applied command to {nodeId} -> channel={new_ch} (after delay)")
            except Exception as e:
                print("[sim] error sending applied status:", e)
            self.pending_apply_timers.pop(timer_key, None)

        def consumer_loop():
            consumer = KafkaConsumer(
                COMMAND_TOPIC,
                bootstrap_servers=self.bootstrap,
                auto_offset_reset="latest",
                value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                consumer_timeout_ms=1000,
                group_id=f"node-sim-consumer-{random.randint(0,99999)}"
            )
            print(f"[sim] command consumer started subscribing to {COMMAND_TOPIC}")
            while not self.stop_event.is_set():
                pol = consumer.poll(timeout_ms=1000, max_records=10)
                for tp, records in pol.items():
                    for record in records:
                        try:
                            value = record.value
                            if not isinstance(value, dict):
                                continue
                            # ignore status/ack messages (they have 'status')
                            if value.get("status") is not None:
                                continue

                            nodeId = value.get("nodeId") or value.get("key") or None
                            command = value.get("command") or value.get("type") or None
                            payload = value.get("payload")
                            if not nodeId or command != "SET_CHANNEL":
                                continue

                            # parse node index
                            if nodeId.startswith("node-"):
                                try:
                                    idx = int(nodeId.split("-")[-1])
                                except Exception:
                                    continue
                            else:
                                continue

                            # parse payload tolerant
                            try:
                                new_ch = int(payload)
                            except Exception:
                                if isinstance(payload, dict) and "channel" in payload:
                                    new_ch = int(payload["channel"])
                                else:
                                    continue

                            if not (1 <= idx <= self.nodes):
                                continue

                            current_ch = self.node_states[idx].channel
                            if current_ch == new_ch:
                                print(f"[sim] command received but node {nodeId} already on channel={new_ch}, ignoring")
                                continue

                            # schedule apply or apply immediately
                            timer_key = f"{nodeId}:{now_iso()}"
                            if self.apply_delay > 0.0:
                                print(f"[sim] scheduling apply for {nodeId} -> channel={new_ch} after {self.apply_delay}s")
                                t = threading.Timer(self.apply_delay, apply_command_delayed, args=(idx, new_ch, nodeId, timer_key))
                                self.pending_apply_timers[timer_key] = t
                                t.daemon = True
                                t.start()
                            else:
                                apply_command_delayed(idx, new_ch, nodeId, timer_key)
                        except Exception as e:
                            print("[sim] command consumer error:", e)
                time.sleep(0.1)
            consumer.close()
            print("[sim] command consumer exiting")

        self.command_consumer_thread = threading.Thread(target=consumer_loop, daemon=True)
        self.command_consumer_thread.start()

    def stop(self):
        self.stop_event.set()
        if self.command_consumer_thread:
            self.command_consumer_thread.join(timeout=2)
        # cancel pending timers
        for k, t in list(self.pending_apply_timers.items()):
            try:
                t.cancel()
            except Exception:
                pass
        try:
            self.producer.flush()
            self.producer.close()
        except Exception:
            pass

    # path loss model: RSSI = REF_RSSI_AT_1M - 10 * n * log10(d)
    def _rssi_from_distance(self, dist_m: float) -> int:
        if dist_m < 1.0:
            dist_m = 1.0
        rssi = REF_RSSI_AT_1M - 10 * PATHLOSS_EXPONENT * math.log10(dist_m)
        # add small random jitter
        rssi += random.uniform(-2.0, 2.0)
        return int(clamp(round(rssi), NOISE_FLOOR, -30))

    # compute channel busy percent (0..100) for each channel from neighbors' recent tx
    def _compute_channel_busy_map_and_raw(self) -> (Dict[int, float], Dict[int, float]):
        # Sum tx contribution in a radius-weighted fashion (global)
        channel_load_raw = {ch: 0.0 for ch in self.channels}
        for i, node in self.node_states.items():
            for ch, tx in node.per_channel_tx.items():
                if ch in channel_load_raw:
                    channel_load_raw[ch] += tx
        max_val = max(1.0, max(channel_load_raw.values()) if channel_load_raw else 1.0)
        # normalize to 0..100 using heuristic scale
        busy_map = {}
        for ch, val in channel_load_raw.items():
            busy = (val / max_val) * 90.0
            busy_map[ch] = clamp(round(busy, 2), 0.0, 100.0)
        return busy_map, channel_load_raw

    def _build_interference_scan_for_node(self, node_idx: int, global_busy_map: Dict[int, float], channel_load_raw: Dict[int, float]) -> List[Dict[str, Any]]:
        node = self.node_states[node_idx]
        scans = []
        for ch in self.channels:
            # contributors among node neighbors
            contributing_nodes = []
            for j in self.neighbors[node_idx]:
                if self.node_states[j].per_channel_tx.get(ch, 0) > 0:
                    contributing_nodes.append(j)

            if contributing_nodes:
                # average distance to transmitters on that channel
                avg_dist = sum(math.hypot(node.pos[0]-self.node_states[j].pos[0],
                                           node.pos[1]-self.node_states[j].pos[1]) for j in contributing_nodes) / len(contributing_nodes)
                rssi = self._rssi_from_distance(avg_dist)
            else:
                # if no contributors, use a distant baseline with jitter
                rssi = int(random.uniform(NOISE_FLOOR, -55))

            # Busy estimate: start from global busy map, amplify if contributors are nearby
            busy = global_busy_map.get(ch, 0.0)
            if contributing_nodes:
                busy = clamp(busy + min(25.0, len(contributing_nodes) * 6.0), 0.0, 100.0)

            # small random jitter
            busy = clamp(round(busy + random.uniform(-4.0, 4.0), 2), 0.0, 100.0)

            scans.append({"channel": ch, "rssi": rssi, "busy": busy})
        return scans

    def _compute_local_operating_busy_for_node(self, node_idx: int, global_busy_map: Dict[int, float], channel_load_raw: Dict[int, float]) -> float:
        """
        Compute node's perceived busy on its operating channel, based on:
          - global busy map
          - node's own recent tx activity (local contribution)
          - neighbor contributors (higher weight)
        Returns a 0..100 float
        """
        node = self.node_states[node_idx]
        ch = node.channel
        global_busy = global_busy_map.get(ch, 0.0)
        local_tx = node.per_channel_tx.get(ch, 0.0)
        # average tx level for this channel
        avg_tx = 0.0
        count = 0
        for i, n in self.node_states.items():
            tx = n.per_channel_tx.get(ch, 0.0)
            if tx > 0:
                avg_tx += tx
                count += 1
        avg_tx = (avg_tx / count) if count > 0 else 0.0

        # local factor: if node's tx is much higher than avg, increase busy
        local_factor = 0.0
        if avg_tx > 0:
            local_factor = clamp((local_tx / (avg_tx + 1.0)) - 1.0, -0.5, 5.0)  # -0.5..5
        # neighbor factor: number of neighbors transmitting on same channel
        neighbor_count = sum(1 for j in self.neighbors[node_idx] if self.node_states[j].per_channel_tx.get(ch, 0) > 0)
        neighbor_factor = clamp(neighbor_count * 0.15, 0.0, 2.0)

        # combine: base from global busy, adjusted by local and neighbor factors, with diminishing returns
        busy = global_busy * (1.0 + 0.4 * local_factor + 0.6 * neighbor_factor)
        busy = clamp(round(busy, 2), 0.0, 100.0)
        return busy

    def _decay_per_channel_tx(self):
        # reduce per_channel_tx values so old activity fades out
        for node in self.node_states.values():
            for ch in list(node.per_channel_tx.keys()):
                node.per_channel_tx[ch] = node.per_channel_tx.get(ch, 0.0) * PER_CHANNEL_TX_DECAY
                # remove tiny residuals
                if node.per_channel_tx[ch] < 1.0:
                    node.per_channel_tx.pop(ch, None)

    def run(self):
        try:
            if self.apply_commands:
                self.start_command_consumer()

            print(f"[sim] starting simulation: nodes={self.nodes} interval={self.interval}s mode={self.mode}")
            while not self.stop_event.is_set():
                cycle_start = time.time()

                # calculate global per-channel busy and raw loads from aggregated tx
                global_busy_map, channel_load_raw = self._compute_channel_busy_map_and_raw()

                for n in range(1, self.nodes + 1):
                    state = self.node_states[n]
                    # compute interference boost and burst factor depending on mode
                    interference_boost = 0.0
                    burst_factor = 1.0
                    if self.mode == "interference" and n in self.interf_nodes:
                        interference_boost = self.interference_boost
                    if self.mode == "burst" and n in self.burst_nodes:
                        burst_factor = self.burst_factor
                    if self.mode == "switching" and self.force_apply and n in self.interf_nodes:
                        state.channel = random.choice(self.channels)

                    # compute local operating busy derived from per-channel tx + global busy
                    local_operating_busy = self._compute_local_operating_busy_for_node(n, global_busy_map, channel_load_raw)
                    # incorporate interference boost (mode)
                    local_operating_busy = clamp(local_operating_busy + interference_boost, 0.0, 100.0)

                    # build per-node interferenceScan based on neighbors and global busy
                    scans = self._build_interference_scan_for_node(n, global_busy_map, channel_load_raw)
                    # build the node's "real" telemetry sample (includes the scan)
                    telemetry = state.to_telemetry(self.channels, local_operating_busy, scans, burst_factor=burst_factor)

                    # Also attach a compact neighbor list (ids)
                    telemetry["neighbors"] = self.neighbors.get(n, [])

                    # send telemetry
                    self.producer.send(TELEMETRY_TOPIC, value=telemetry)
                    if n % 20 == 0:
                        self.producer.flush()
                    print(f"Produced: {telemetry['nodeId']} ch={telemetry['channel']} busy={telemetry['channelBusyPercent']} rssi={telemetry['rssi']} clients={telemetry['numClients']}")

                # decay old tx contributions so busy map reflects recent cycles
                self._decay_per_channel_tx()

                elapsed = time.time() - cycle_start
                to_sleep = max(0, self.interval - elapsed)
                time.sleep(to_sleep)
        except KeyboardInterrupt:
            print("[sim] keyboard interrupt, stopping")
        finally:
            print("[sim] stopping, flushing")
            self.stop()

def parse_args():
    p = argparse.ArgumentParser(description="Enhanced WMN Node Simulator (correlated telemetry)")
    p.add_argument("--nodes", "-n", type=int, default=DEFAULT_NODES, help="Number of nodes")
    p.add_argument("--interval", "-i", type=float, default=DEFAULT_INTERVAL, help="Seconds per full cycle")
    p.add_argument("--bootstrap", "-b", default=KAFKA_BOOTSTRAP, help="Kafka bootstrap servers")
    p.add_argument("--topic", "-t", default=TELEMETRY_TOPIC, help="Telemetry topic")
    p.add_argument("--channels", "-c", default=",".join(map(str, DEFAULT_CHANNELS)), help="Comma separated channel list")
    p.add_argument("--mode", "-m", choices=["baseline","interference","burst","switching"], default="baseline", help="Simulation mode")
    p.add_argument("--interference-pct", type=float, default=0.2, help="Fraction of nodes under interference (0-1)")
    p.add_argument("--interference-boost", type=float, default=30.0, help="Add to channelBusyPercent for interference nodes")
    p.add_argument("--burst-pct", type=float, default=0.0, help="Fraction of nodes that will burst")
    p.add_argument("--burst-factor", type=float, default=3.0, help="Multiplier for tx/rx bytes in burst nodes")
    p.add_argument("--seed", type=int, help="Random seed for repeatability")
    p.add_argument("--apply-commands", action="store_true", help="Start internal consumer to apply SET_CHANNEL commands")
    p.add_argument("--force-apply", action="store_true", help="In 'switching' mode, force channel changes locally (fast test)")
    p.add_argument("--apply-delay", type=float, default=0.0, help="Seconds to wait before applying an incoming SET_CHANNEL (simulate switch delay)")
    p.add_argument("--apply-fail-rate", type=float, default=0.0, help="Probability [0..1] that applying a command fails (simulate unreliable devices)")
    p.add_argument("--neighbor-radius", type=float, default=DEFAULT_NEIGHBOR_RADIUS, help="Neighbor radius in meters for spatial correlation")
    p.add_argument("--area-size", type=float, default=DEFAULT_AREA_SIZE, help="Square area side length in meters for node placement")
    return p.parse_args()

def main():
    args = parse_args()
    channels = [int(x) for x in args.channels.split(",") if x.strip()]
    sim = NodeSimulator(
        bootstrap=args.bootstrap,
        nodes=args.nodes,
        interval=args.interval,
        channels=channels,
        mode=args.mode,
        interference_pct=args.interference_pct,
        interference_boost=args.interference_boost,
        seed=args.seed,
        burst_pct=args.burst_pct,
        burst_factor=args.burst_factor,
        apply_commands=args.apply_commands,
        force_apply=args.force_apply,
        apply_delay=args.apply_delay,
        apply_fail_rate=args.apply_fail_rate,
        neighbor_radius=args.neighbor_radius,
        area_size=args.area_size
    )
    try:
        sim.run()
    except Exception as e:
        print("[sim] fatal error:", e)
    finally:
        sim.stop()

if __name__ == "__main__":
    main()
