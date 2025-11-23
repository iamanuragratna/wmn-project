import { Component, Input, DoCheck } from '@angular/core';
import { CommonModule } from '@angular/common';
import { KeysPipe } from '../../pipes/keys.pipe';

@Component({
  selector: 'app-telemetry-chart',
  standalone: true,
  imports: [CommonModule, KeysPipe],
  templateUrl: './telemetry-chart.component.html',
  styleUrls: ['./telemetry-chart.component.css']
})
export class TelemetryChartComponent implements DoCheck {
  @Input() telemetry: { [nodeId: string]: any[] } = {};

  nodeIds: string[] = [];
  selectedNode?: string;
  chartData: any[] = [];
  pointsBusy = '';
  pointsRssi = '';

  public Object = Object;
  private _lastKeysJson = '';

  ngDoCheck() {
    const keys = this.telemetry ? Object.keys(this.telemetry) : [];
    const keysJson = JSON.stringify(keys);
    if (keysJson !== this._lastKeysJson) {
      this._lastKeysJson = keysJson;
      this.nodeIds = keys;
      console.log('[TelemetryChart] doCheck detected keys=', this.nodeIds);
      if (!this.selectedNode && this.nodeIds.length) this.selectedNode = this.nodeIds[0];
      else if (this.selectedNode && !this.nodeIds.includes(this.selectedNode) && this.nodeIds.length) this.selectedNode = this.nodeIds[0];
      this.updateChart();
    } else {
      // if selected node exists and array length changed, update
      if (this.selectedNode) {
        const arr = (this.telemetry[this.selectedNode] || []);
        const lastLen = this.chartData ? this.chartData.length : 0;
        if (arr.length !== lastLen) this.updateChart();
      }
    }
  }

  updateChart() {
    if (!this.selectedNode) {
      this.chartData = [];
      this.pointsBusy = '';
      this.pointsRssi = '';
      return;
    }

    const raw = (this.telemetry[this.selectedNode] || []).slice(-500);
    if (!raw || raw.length === 0) {
      this.chartData = [];
      this.pointsBusy = '';
      this.pointsRssi = '';
      console.log('[TelemetryChart] updateChart no raw data for', this.selectedNode);
      return;
    }

    const series = raw
      .map(s => ({
        ts: s.ts ? new Date(s.ts).getTime() : (s.windowEnd ? new Date(s.windowEnd).getTime() : Date.now()),
        busy: (s.busy != null) ? Number(s.busy) : (s.avgChannelBusyPercent != null ? Number(s.avgChannelBusyPercent) : null),
        rssi: (s.rssi != null) ? Number(s.rssi) : (s.avgRssi != null ? Number(s.avgRssi) : null),
        txBytes: s.txBytes != null ? Number(s.txBytes) : (s.sumTxBytes != null ? Number(s.sumTxBytes) : null),
        __forecast: !!s.__forecast
      }))
      .filter(p => p.ts && (p.busy !== null || p.rssi !== null))
      .sort((a, b) => a.ts - b.ts);

    if (series.length === 0) {
      this.chartData = [];
      this.pointsBusy = '';
      this.pointsRssi = '';
      console.warn('[TelemetryChart] normalized series empty for', this.selectedNode);
      return;
    }

    this.chartData = series;

    // Build points. Use (i/(len-1))*800 when len>1 so points occupy full width.
    const len = Math.max(series.length, 1);
    const busyPoints: string[] = [];
    const rssiPoints: string[] = [];

    for (let i = 0; i < series.length; i++) {
      const p = series[i];
      const x = (len > 1) ? Math.round((i / (len - 1)) * 800) : 400; // center single point
      const busyVal = isFinite(Number(p.busy)) ? Number(p.busy) : 0;
      let yBusy = 200 - (busyVal * 1.6);
      if (!isFinite(yBusy)) yBusy = 200;
      busyPoints.push(`${x},${Math.round(yBusy)}`);

      const rssiVal = isFinite(Number(p.rssi)) ? Number(p.rssi) : -100;
      let yRssi = 200 - ((rssiVal + 100) * 1.2);
      if (!isFinite(yRssi)) yRssi = 200;
      rssiPoints.push(`${x},${Math.round(yRssi)}`);
    }

    this.pointsBusy = busyPoints.join(' ');
    this.pointsRssi = rssiPoints.join(' ');
    console.log('[TelemetryChart] built points for', this.selectedNode, 'count=', busyPoints.length);
  }

  selectNode(id: string) {
    this.selectedNode = id;
    this.updateChart();
  }

  formatTime(ts: number) {
    const d = new Date(ts);
    return d.getHours() + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2);
  }

  // Limit debug output to last N samples and pretty-print
  getDebugSamples(limit = 50): string {
    try {
      if (!this.selectedNode) return 'no node selected';
      const arr = (this.telemetry[this.selectedNode] || []).slice(-limit);
      return JSON.stringify(arr, null, 2);
    } catch (e) {
      return 'debug stringify error';
    }
  }
}
