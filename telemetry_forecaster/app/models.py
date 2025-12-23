from pydantic import BaseModel
from typing import Optional

class FeatureModel(BaseModel):
    nodeId: str
    windowStart: Optional[str]
    windowEnd: Optional[str]
    granularity: Optional[str]
    sampleCount: Optional[int] = 0
    avgChannelBusyPercent: Optional[float] = 0.0
    maxChannelBusyPercent: Optional[float] = 0.0
    minRssi: Optional[int] = 0
    avgRssi: Optional[float] = 0.0
    sumTxBytes: Optional[int] = 0
    lastSeen: Optional[str] = None
    channel: Optional[int] = 0
    synthetic: Optional[bool] = False
    avgNumClients: Optional[float] = 0.0
