        telemetry_forecaster

        FastAPI-based forecasting service that consumes aggregated features from Kafka topic `wmn.features.v1` and publishes simple forecasts to `wmn.forecasts.v1`.


        Quick start (Windows PowerShell):

 1. Ensure Kafka is running and topics exist:
    docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.features.v1 --partitions 3 --replication-factor 1 || true"
    docker exec -it wmn-project-kafka-1 bash -c "kafka-topics --bootstrap-server localhost:9092 --create --topic wmn.forecasts.v1 --partitions 3 --replication-factor 1 || true"

 2. Install Python deps (use virtualenv):
    python -m venv venv
    .\venv\Scripts\activate
    pip install -r requirements.txt

 3. Run the service:
    uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload

 4. Verify:
    - Check logs for consumer receiving messages
    - Consume forecasts:
      docker exec -it wmn-project-kafka-1 bash -c "kafka-console-consumer --bootstrap-server localhost:9092 --topic wmn.forecasts.v1 --from-beginning --max-messages 5"

Notes:
 - This service uses a small in-memory buffer per node to build time-series of `avgChannelBusyPercent` and produces a 1-step forecast using ARIMA (if enough samples) otherwise a simple moving average.
 - For production use, replace in-memory buffers with persistent store and improve model training lifecycle.
