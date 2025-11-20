import os, asyncio, json, logging
from aiokafka import AIOKafkaConsumer
from .models import FeatureModel

KAFKA_BOOTSTRAP = os.getenv("BOOTSTRAP_SERVERS", "localhost:9092")
FEATURE_TOPIC = os.getenv("FEATURE_TOPIC", "wmn.features.v1")
GROUP_ID = os.getenv("GROUP_ID", "forecast-service")

class FeatureConsumer:
    def __init__(self, service, loop=None):
        self.service = service
        self.loop = loop or asyncio.get_event_loop()
        self.consumer = AIOKafkaConsumer(
            FEATURE_TOPIC,
            loop=self.loop,
            bootstrap_servers=KAFKA_BOOTSTRAP,
            group_id=GROUP_ID,
            auto_offset_reset="earliest"
        )
        self._task = None
        self._running = False

    async def start(self):
        logging.info("Starting aiokafka consumer for topic %s", FEATURE_TOPIC)
        await self.consumer.start()
        self._running = True
        self._task = asyncio.create_task(self._consume_loop())

    async def stop(self):
        logging.info("Stopping aiokafka consumer")
        self._running = False
        if self._task:
            await self._task
        await self.consumer.stop()

    async def _consume_loop(self):
        try:
            async for msg in self.consumer:
                try:
                    payload = msg.value.decode("utf-8")
                    data = json.loads(payload)
                    feature = FeatureModel.parse_obj(data)
                    logging.info("Received feature node=%s samples=%s", feature.nodeId, feature.sampleCount)
                    # call service.add_feature asynchronously
                    await self.service.add_feature(feature)
                except Exception as ex:
                    logging.exception("Failed to handle message: %s", ex)
        except asyncio.CancelledError:
            logging.info("Consumer loop cancelled")
        except Exception as ex:
            logging.exception("Consumer error: %s", ex)
