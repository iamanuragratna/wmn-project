import collections, logging
from datetime import datetime
import numpy as np
import pandas as pd
from .producer import ForecastProducer
from .models import FeatureModel

class ForecastService:
    def __init__(self, producer: ForecastProducer, max_len=120):
        self.buffers = collections.defaultdict(collections.deque)  # nodeId -> deque[(ts, val)]
        self.max_len = max_len
        self.producer = producer

    async def add_feature(self, feature: FeatureModel):
        try:
            ts = feature.windowEnd or feature.windowStart or datetime.utcnow().isoformat() + "Z"
            dt = pd.to_datetime(ts)
            val = float(feature.avgChannelBusyPercent or 0.0)
            dq = self.buffers[feature.nodeId]
            dq.append((dt, val))
            while len(dq) > self.max_len:
                dq.popleft()
            # produce forecast asynchronously
            await self._maybe_forecast(feature.nodeId)
        except Exception as ex:
            logging.exception("add_feature error: %s", ex)

    async def _maybe_forecast(self, nodeId: str):
        dq = self.buffers[nodeId]
        if len(dq) < 6:
            if len(dq) == 0:
                return
            values = [v for (_, v) in dq]
            forecast_val = float(np.mean(values))
            await self._publish(nodeId, forecast_val, method='ma', window_seconds=60)
            return
        try:
            df = pd.DataFrame(list(dq), columns=['ts','val']).set_index('ts').sort_index()
            s = df['val'].resample('1T').mean().ffill().fillna(method='ffill').fillna(0.0)
            s = s[-60:]
            # quick AR(1)-like fallback without statsmodels: use simple autoregressive estimate
            # (we avoid heavy statsmodels install in this lite setup).
            if len(s) < 2:
                forecast_val = float(s.mean())
            else:
                # naive AR(1) estimate: new = last + phi*(last - prev) with phi ~ 0.5
                last = float(s.iloc[-1])
                prev = float(s.iloc[-2])
                phi = 0.5
                forecast_val = last + phi * (last - prev)
            await self._publish(nodeId, forecast_val, method='ar1-approx', window_seconds=60)
        except Exception as ex:
            logging.exception("forecast error: %s", ex)
            values = [v for (_, v) in dq]
            forecast_val = float(np.mean(values))
            await self._publish(nodeId, forecast_val, method='fallback', window_seconds=60)

    async def _publish(self, nodeId: str, forecast_val: float, method='ma', window_seconds=60):
        payload = {
            'nodeId': nodeId,
            'timestamp': datetime.utcnow().isoformat() + 'Z',
            'forecastBusyPercent': round(float(forecast_val), 4),
            'method': method,
            'windowSeconds': window_seconds
        }
        logging.info('Publishing forecast %s', payload)
        try:
            await self.producer.publish(payload)
        except Exception as ex:
            logging.exception('publish error: %s', ex)
