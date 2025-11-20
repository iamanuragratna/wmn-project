import os, json, asyncio, logging
from aiokafka import AIOKafkaProducer

KAFKA_BOOTSTRAP = os.getenv("BOOTSTRAP_SERVERS", "localhost:9092")
FORECAST_TOPIC = os.getenv("FORECAST_TOPIC", "wmn.forecasts.v1")

class ForecastProducer:
    def __init__(self, loop=None):
        self.loop = loop or asyncio.get_event_loop()
        self._producer = AIOKafkaProducer(bootstrap_servers=KAFKA_BOOTSTRAP, loop=self.loop)
        self._started = False

    async def start(self):
        if not self._started:
            logging.info("Starting aiokafka producer...")
            await self._producer.start()
            self._started = True

    async def stop(self):
        if self._started:
            logging.info("Stopping aiokafka producer...")
            await self._producer.stop()
            self._started = False

    async def publish(self, forecast: dict):
        payload = json.dumps(forecast).encode("utf-8")
        # send and wait for ack
        try:
            await self._producer.send_and_wait(FORECAST_TOPIC, payload)
            logging.info("Published forecast for node=%s", forecast.get("nodeId"))
        except Exception as ex:
            logging.exception("Publish failed: %s", ex)
