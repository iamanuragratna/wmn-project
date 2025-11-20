from fastapi import FastAPI
import logging, asyncio, os
from .producer import ForecastProducer
from .service import ForecastService
from .consumer import FeatureConsumer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = FastAPI(title="Telemetry Forecaster")
loop = asyncio.get_event_loop()

# instantiate producer & service (singletons)
producer = ForecastProducer(loop=loop)
service = ForecastService(producer=producer)
consumer = FeatureConsumer(service=service, loop=loop)

@app.on_event("startup")
async def startup_event():
    logging.info("Forecaster startup: starting producer & consumer")
    await producer.start()
    await consumer.start()

@app.on_event("shutdown")
async def shutdown_event():
    logging.info("Forecaster shutdown: stopping consumer & producer")
    await consumer.stop()
    await producer.stop()

@app.get("/health")
async def health():
    return {"status": "ok"}

@app.get("/stats")
async def stats():
    return {
        "nodes_tracked": len(service.buffers),
        "buffer_sizes": {k: len(v) for k, v in service.buffers.items()}
    }
