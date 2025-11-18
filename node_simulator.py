#!/usr/bin/env python3
"""
Node Simulator for WMN telemetry

Produces JSON telemetry messages to Kafka topic `wmn.telemetry.v1`.

Usage:
  - edit CONFIG below to adjust NUM_NODES, INTERVAL_SEC, CHANNELS
  - run: python node_simulator.py
  - stop: Ctrl+C
"""

import json
import random
import time
import argparse
from datetime import datetime, timezone
from kafka import KafkaProducer

# -------------------------
# CONFIG (edit if needed)
# -------------------------
KAFKA_BOOTSTRAP = "localhost:9092"
KAFKA_TOPIC = "wmn.telemetry.v1"
NUM_NODES = 10            # number of simulated mesh nodes
INTERVAL_SEC = 3          # seconds between cycles through all nodes
CHANNELS = [1, 6, 11]     # channels to pick from
NODE_ID_PREFIX = "node-"
LINGER_MS = 5             # Kafka producer linger
ACKS = 1                  # Kafka acks (1 = leader ack)
# -------------------------

def make_producer(bootstrap_servers: str) -> KafkaProducer:
    """Create Kafka producer with JSON serializer."""
    return KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        linger_ms=LINGER_MS,
        acks=ACKS
    )

def random_interference_scan():
    """Synthetic interference scan — list of {channel, rssi}."""
    return [{"channel": ch, "rssi": random.randint(-95, -40)} for ch in CHANNELS]

def gen_telemetry(node_idx: int):
    """Generate one telemetry JSON for a given node index."""
    node_id = f"{NODE_ID_PREFIX}{node_idx:03d}"
    channel = random.choice(CHANNELS)
    rssi = random.randint(-85, -40)
    snr = random.randint(5, 40)
    tx_bytes = random.randint(0, 200000)
    rx_bytes = random.randint(0, 200000)
    tx_retries = random.randint(0, 200)
    num_clients = max(0, int(random.gauss(5, 3)))
    busy = round(random.uniform(0, 100), 2)

    payload = {
        "nodeId": node_id,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "radioId": "wlan0",
        "channel": channel,
        "rssi": rssi,
        "snr": snr,
        "txBytes": tx_bytes,
        "rxBytes": rx_bytes,
        "txRetries": tx_retries,
        "numClients": num_clients,
        "channelBusyPercent": busy,
        "interferenceScan": random_interference_scan()
    }
    return payload

def run_loop(producer: KafkaProducer, num_nodes: int, interval_sec: float):
    """Main loop: produce telemetry for num_nodes repeatedly."""
    print(f"Simulator starting: {num_nodes} nodes, interval {interval_sec}s (cycle). Ctrl+C to stop.")
    try:
        while True:
            start = time.time()
            for n in range(1, num_nodes + 1):
                msg = gen_telemetry(n)
                producer.send(KAFKA_TOPIC, value=msg)
                # periodic flush to avoid too-large buffers
                if n % 20 == 0:
                    producer.flush()
                print(f"Produced: {msg['nodeId']} channel={msg['channel']} rssi={msg['rssi']} clients={msg['numClients']}")
            # ensure we respect interval per cycle
            elapsed = time.time() - start
            sleep_time = max(0, interval_sec - elapsed)
            time.sleep(sleep_time)
    except KeyboardInterrupt:
        print("\nSimulator stopping (keyboard interrupt). Flushing producer and exiting...")
    finally:
        producer.flush()
        producer.close()
        print("Producer closed. Goodbye.")

def parse_args():
    p = argparse.ArgumentParser(description="WMN Node Simulator")
    p.add_argument("--nodes", "-n", type=int, default=NUM_NODES, help="Number of nodes to simulate")
    p.add_argument("--interval", "-i", type=float, default=INTERVAL_SEC, help="Seconds per full cycle of all nodes")
    p.add_argument("--bootstrap", "-b", default=KAFKA_BOOTSTRAP, help="Kafka bootstrap servers")
    p.add_argument("--topic", "-t", default=KAFKA_TOPIC, help="Kafka topic to publish telemetry")
    return p.parse_args()

def main():
    args = parse_args()
    prod = make_producer(args.bootstrap)
    global KAFKA_TOPIC
    KAFKA_TOPIC = args.topic
    run_loop(prod, args.nodes, args.interval)

if __name__ == "__main__":
    main()
