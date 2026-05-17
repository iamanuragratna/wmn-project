Start docker desktop

1) show existing kafka topics
PS C:\wmn-ui\wmn-frontend> docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --list"
wmn.chconfigs.v1
wmn.commands.v1
wmn.features.v1
wmn.forecasts.v1
wmn.telemetry.v1

2) delete existing kafka topics
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --delete --topic wmn.chconfigs.v1"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --delete --topic wmn.commands.v1"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --delete --topic wmn.features.v1"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --delete --topic wmn.forecasts.v1"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --delete --topic wmn.telemetry.v1"

3) create kafka topic
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.telemetry.v1 --partitions 3 --replication-factor 1 || true"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.features.v1 --partitions 3 --replication-factor 1 || true"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.forecasts.v1 --partitions 3 --replication-factor 1 || true"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.chconfigs.v1 --partitions 3 --replication-factor 1 || true"
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.commands.v1 --partitions 3 --replication-factor 1 || true"


4) test node_stimulator.py    
cd C:\wmn-project
.\venv\Scripts\activate	
python node_simulator.py --nodes 10 --interval 3 --mode baseline --apply-commands --seed 42
python node_simulator.py --nodes 40 --interval 3 --mode interference --interference-pct 0.2 --interference-boost 30 --apply-commands --seed 7001
python node_simulator.py --nodes 5 --interval 5 --mode interference --interference-pct 0.2 --interference-boost 25 --burst-pct 0.1 --burst-factor 4 --apply-commands --seed 10001

python C:\wmn-project\node_simulator.py --nodes 10 --interval 3 --bootstrap localhost:9092 --topic wmn.telemetry.v1
(venv) PS C:\wmn-project> python C:\wmn-project\node_simulator.py --nodes 5 --interval 10 --bootstrap localhost:9092 --topic wmn.telemetry.v1
[sim] starting simulation: nodes=1 interval=3.0s mode=baseline
Produced: node-001 ch=1 busy=41.27 rssi=-62 clients=6
Produced: node-001 ch=1 busy=31.34 rssi=-62 clients=6
[sim] keyboard interrupt, stopping
[sim] stopping, flushing

it's working.

5) run telemetry-ingest microservice
it's working http://localhost:8080/api/telemetry/node/node-001
delete the plsql data

6) create kafka topic
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.features.v1 --partitions 3 --replication-factor 1 || true"

7) run telemetry-aggregation microservice
it's working 
what it does?
Accepts telemetry samples concurrently and stores them per node/channel.
Keeps per-channel deques bounded by maxSamplesPerChannel.
Periodically (when aggregateAndPublish() is called) prunes samples older than windowSeconds and computes aggregated metrics for each channel of each node.
Publishes a Feature for each (node,channel) that has at least one sample in the window via StreamBridge.

if stimular stop , telemetry -aggregration take some time but it stop publishing as well after a min.

8) create kafka topic 
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.forecasts.v1 --partitions 3 --replication-factor 1 || true"

9) run telemetry-forcaster
cd C:\wmn-project\telemetry_forecaster\
.\venv\Scripts\activate	
uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
it's working

10) create kafka topic 
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.chconfigs.v1 --partitions 3 --replication-factor 1 || true"

11) start telemetry-optimizer 
iy;s working

12) create kafka topic 
docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.commands.v1 --partitions 3 --replication-factor 1 || true"

13) start telemetry-conyroller
it's working
