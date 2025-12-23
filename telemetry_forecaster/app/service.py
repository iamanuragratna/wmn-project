# forecast_service.py
import collections
import logging
import asyncio
import signal
from datetime import datetime
from typing import Dict, Deque, Tuple

import numpy as np
import pandas as pd

# Import your ForecastProducer and FeatureModel per your project layout
from .producer import ForecastProducer
from .models import FeatureModel

logger = logging.getLogger("forecast")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


class ForecastService:
    def __init__(self, producer: ForecastProducer, max_len: int = 240):
        """
        producer: ForecastProducer with async publish(payload) method
        max_len: how many historical feature samples to keep per (node,channel)
        """
        # buffers[nodeId][channel] -> deque of (ts: pd.Timestamp, val: float)
        self.buffers: Dict[str, Dict[int, Deque[Tuple[pd.Timestamp, float]]]] = \
            collections.defaultdict(lambda: collections.defaultdict(collections.deque))
        self.max_len = max_len
        self.producer = producer

        # used to track background work if you care to inspect
        self._pending_publish_futures = set()

        # lock to protect pending futures set
        self._pending_lock = asyncio.Lock()

        # shut down indicator
        self._closing = False

    async def add_feature(self, feature: FeatureModel):
        """
        Consume a FeatureModel and append it into the per-node-per-channel buffer.

        Expected fields on FeatureModel:
            - nodeId (str)
            - channel (int)
            - windowEnd or windowStart (timestamp string)
            - avgChannelBusyPercent (float)
            - sampleCount (int)
            - synthetic (bool)  # optional
            - avgNumClients (float)  # optional => forwarded to forecasts
        """
        try:
            if feature is None:
                return

            node = getattr(feature, "nodeId", None)
            ch = getattr(feature, "channel", None)
            if node is None or ch is None:
                logger.debug("Dropping feature with missing node/channel: %s", feature)
                return

            # timestamp parsing
            ts_str = getattr(feature, "windowEnd", None) or getattr(feature, "windowStart", None) \
                     or (datetime.utcnow().isoformat() + "Z")
            dt = pd.to_datetime(ts_str)

            # metric
            if getattr(feature, "avgChannelBusyPercent", None) is None:
                logger.debug("Dropping feature with null avgChannelBusyPercent: %s", feature)
                return

            val = float(feature.avgChannelBusyPercent)
            nodeId = str(node)
            channel = int(ch)
            avgNumClients = float(getattr(feature, "avgNumClients", 0.0))
            is_synth = bool(getattr(feature, "synthetic", False))

            # append to buffer and enforce max length
            dq = self.buffers[nodeId][channel]
            dq.append((dt, val))
            while len(dq) > self.max_len:
                dq.popleft()

            # logging for visibility
            logger.info("Received feature node=%s channel=%s samples=%d synthetic=%s avgNumClients=%s",
                        nodeId, channel, len(dq), is_synth, avgNumClients)

            # trigger forecasting logic
            await self._maybe_forecast(nodeId, channel, is_synth, avgNumClients)

        except Exception as ex:
            logger.exception("add_feature error: %s", ex)

    async def _maybe_forecast(self, nodeId: str, channel: int, is_synth: bool, avgNumClients: float):
        """
        Decide whether to compute a forecast now (AR1 path) or emit a simple moving average.
        Runs in the asyncio event loop.
        """
        try:
            dq = self.buffers[nodeId][channel]
            n = len(dq)
            if n == 0:
                return

            min_samples_for_ar1 = 6 if not is_synth else 8

            # If insufficient samples, publish a simple moving average (low confidence)
            if n < min_samples_for_ar1:
                values = [v for (_, v) in dq]
                forecast_val = float(np.mean(values)) if values else 0.0
                confidence = min(0.49, float(n) / float(min_samples_for_ar1))
                await self._publish(nodeId, channel, forecast_val,
                                    method='ma',
                                    window_seconds=60,
                                    sampleCount=n,
                                    confidence=confidence,
                                    synthetic=is_synth,
                                    avgNumClients=avgNumClients)
                return

            # For AR(1)-like computation, run the heavy part in a thread but schedule publish on the main loop.
            loop = asyncio.get_running_loop()
            # pass buffers snapshot to thread to avoid concurrent modifications
            dq_snapshot = list(dq)
            # Note: we do NOT wait here for publish to finish — thread will schedule publish on main loop.
            await asyncio.to_thread(self._compute_and_publish, nodeId, channel, dq_snapshot, is_synth, avgNumClients, loop)

        except Exception as ex:
            logger.exception("_maybe_forecast error: %s", ex)
            # fallback: attempt to publish a simple mean
            try:
                dq = self.buffers[nodeId][channel]
                values = [v for (_, v) in dq]
                forecast_val = float(np.mean(values)) if values else 0.0
                await self._publish(nodeId, channel, forecast_val,
                                    method='fallback',
                                    window_seconds=60,
                                    sampleCount=len(values),
                                    confidence=0.2,
                                    synthetic=is_synth,
                                    avgNumClients=avgNumClients)
            except Exception:
                logger.exception("fallback publish also failed")

    def _compute_and_publish(self, nodeId: str, channel: int, dq_list, is_synth: bool, avgNumClients: float, loop: asyncio.AbstractEventLoop):
        """
        Runs in a worker thread (called via asyncio.to_thread).
        Compute the AR(1)-like forecast value and schedule an async publish on the main loop.
        We DO NOT call asyncio.run() here. Instead, we use run_coroutine_threadsafe.
        """
        try:
            # build dataframe and resample
            df = pd.DataFrame(dq_list, columns=['ts', 'val']).set_index('ts').sort_index()
            s = df['val'].resample('1min').mean().ffill().fillna(0.0)  # '1min' avoids deprecated 'T' alias
            s = s[-60:]
            sample_count = int(len(df))

            if len(s) < 2:
                forecast_val = float(s.mean()) if len(s) > 0 else 0.0
                variance = float(s.var()) if len(s) > 1 else 0.0
                confidence = min(0.5, 0.1 + sample_count / 100.0)
                method = 'ma'
            else:
                last = float(s.iloc[-1])
                prev = float(s.iloc[-2])
                phi = 0.5
                forecast_val = last + phi * (last - prev)
                forecast_val = max(0.0, min(100.0, forecast_val))
                variance = float(s.var()) if len(s) > 1 else 0.0
                confidence = min(0.99, 0.5 + sample_count / 100.0 - variance / 200.0)
                method = 'ar1-approx'

            if is_synth:
                confidence = confidence * 0.5

            confidence = max(0.0, min(1.0, confidence))

            # prepare coroutine for publish
            coro = self._publish(nodeId, channel, forecast_val,
                                 method=method,
                                 window_seconds=60,
                                 sampleCount=sample_count,
                                 confidence=confidence,
                                 synthetic=is_synth,
                                 avgNumClients=avgNumClients)

            # schedule on main loop without blocking the thread; keep a handle to the future for optional tracking
            fut = asyncio.run_coroutine_threadsafe(coro, loop)

            # track pending futures (fire-and-forget style); do not block waiting on them
            # we use add_done_callback to log completion and clean up
            def _done_cb(f):
                try:
                    res = f.result()  # may raise if publish failed
                    logger.debug("Publish completed for %s ch=%s", nodeId, channel)
                except Exception as e:
                    logger.exception("Publish future raised: %s", e)
                # we can't use 'await' here; futures will be managed by loop
                # remove from pending set in a thread-safe way via asyncio.ensure_future on loop
                try:
                    if asyncio.get_event_loop() is loop:
                        # same loop -> safe to remove synchronously
                        pass
                except Exception:
                    pass

            fut.add_done_callback(_done_cb)

            # NOTE: we intentionally do NOT call fut.result() here to avoid blocking the worker thread.
        except Exception as ex:
            logger.exception("_compute_and_publish error: %s", ex)
            # best-effort fallback: schedule a simple mean publish
            try:
                values = [v for (_, v) in dq_list]
                forecast_val = float(np.mean(values)) if values else 0.0
                coro = self._publish(nodeId, channel, forecast_val,
                                     method='fallback',
                                     window_seconds=60,
                                     sampleCount=len(dq_list),
                                     confidence=0.2,
                                     synthetic=is_synth,
                                     avgNumClients=avgNumClients)
                asyncio.run_coroutine_threadsafe(coro, loop)
            except Exception:
                logger.exception("fallback publish also failed in thread")

    async def _publish(self, nodeId: str, channel: int, forecast_val: float,
                       method: str = 'ma', window_seconds: int = 60, sampleCount: int = 0,
                       confidence: float = 0.0, synthetic: bool = False, avgNumClients: float = 0.0):
        """
        Construct forecast payload and publish via the async producer.
        This runs in the event loop.
        """
        try:
            payload = {
                'nodeId': nodeId,
                'channel': channel,
                'timestamp': datetime.utcnow().isoformat() + 'Z',
                'forecastBusyPercent': round(float(forecast_val), 4),
                'method': method,
                'windowSeconds': window_seconds,
                'sampleCount': sampleCount,
                'confidence': round(float(confidence), 4),
                'synthetic': bool(synthetic),
                'avgNumClients': float(avgNumClients)
            }
            logger.info("Publishing forecast %s", payload)
            await self.producer.publish(payload)
        except Exception as ex:
            logger.exception("publish error: %s", ex)

    async def close(self):
        """
        Gracefully shutdown the ForecastService. Waits for a short period for pending publishes.
        """
        if self._closing:
            return
        logger.info("ForecastService: shutting down")
        self._closing = True
        # give some time for background publishes to complete if any (they are scheduled on the loop)
        await asyncio.sleep(0.2)
        logger.info("ForecastService: closed")


# ----------------------------
# Demo runner: shows how to run and handle Ctrl+C cleanly
# ----------------------------
async def _demo_run_forecast_service():
    """
    Demo: create a ForecastService with a dummy producer (prints) and wait until Ctrl+C.
    Replace this demo usage with your actual application bootstrap.
    """
    class DummyProducer:
        async def publish(self, payload):
            # mimic async publish latency
            await asyncio.sleep(0.01)
            logger.info("DummyProducer published: %s", payload)

    producer = DummyProducer()
    svc = ForecastService(producer=producer, max_len=120)

    # Install simple signal handlers to cancel the loop gracefully
    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _on_signal():
        logger.info("Received shutdown signal")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _on_signal)
        except NotImplementedError:
            # Some platforms (Windows / certain envs) may not implement add_signal_handler
            pass

    logger.info("Demo ForecastService started. Press Ctrl+C to stop.")
    try:
        # wait until signalled
        await stop_event.wait()
    finally:
        await svc.close()


if __name__ == "__main__":
    # Run the demo if executed directly. In your real service, call ForecastService() from your app's startup.
    try:
        asyncio.run(_demo_run_forecast_service())
    except KeyboardInterrupt:
        # fallback for environments where loop signal handlers aren't available
        logger.info("KeyboardInterrupt received, exiting")
    except Exception as e:
        logger.exception("Fatal error in demo runner: %s", e)
