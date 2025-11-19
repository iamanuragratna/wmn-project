telemetry_aggregation

Spring Cloud Stream (Kafka binder) based aggregation service.


Quick start:

1. Ensure kafka/zookeeper are running and topics exist: wmn.telemetry.v1 and wmn.features.v1

2. mvn -DskipTests clean package
3. java -jar target/telemetry-aggregation-0.0.1-SNAPSHOT.jar


The app consumes telemetry, stores recent samples per node in-memory, and publishes aggregated
Feature objects every processor.schedule.ms (default 15000ms) covering the last processor.window.seconds (default 60s).
