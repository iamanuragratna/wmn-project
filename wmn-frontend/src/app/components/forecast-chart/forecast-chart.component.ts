import { Component, Input, DoCheck } from '@angular/core';
import { CommonModule } from '@angular/common';
import { KeysPipe } from '../../pipes/keys.pipe';

@Component({
  selector: 'app-forecast-chart',
  standalone: true,
  imports: [CommonModule, KeysPipe],
  templateUrl: './forecast-chart.component.html',
  styleUrls: ['./forecast-chart.component.css']
})
export class ForecastChartComponent implements DoCheck {
  /** input from parent: map nodeId -> forecast object (or whatever arrives) */
  @Input() forecasts: { [nodeId: string]: any } = {};

  // expose Object to template
  public Object = Object;

  // derived arrays the template should read
  nodeIds: string[] = [];
  nodeForecastDatasets: Array<{ nodeId: string; label: string; value: number; raw: any }> = [];

  // small cache to detect key-set changes (fast)
  private _lastKeysJson = '';

  ngDoCheck() {
    const keys = this.forecasts ? Object.keys(this.forecasts) : [];
    const keysJson = JSON.stringify(keys);

    // Always rebuild datasets when keys change. (If you want to rebuild on value change too,
    // you can expand the cache to include a small snapshot of values.)
    if (keysJson !== this._lastKeysJson) {
      this._lastKeysJson = keysJson;
      this.nodeIds = keys;
      this.buildDatasets();
      console.log('[ForecastChart] doCheck detected keys=', this.nodeIds);
      return;
    }

    // If keys unchanged still rebuild datasets defensively (cheap) — ensures template shows latest values
    // (Optional: comment this out if noisy)
    this.buildDatasets();
  }

  private buildDatasets() {
    const datasets: Array<{ nodeId: string; label: string; value: number; raw: any }> = [];

    if (!this.forecasts) {
      this.nodeForecastDatasets = [];
      return;
    }

    for (const nodeId of Object.keys(this.forecasts)) {
      const f = this.forecasts[nodeId];

      // tolerant access: support either a single object {forecastBusyPercent: ...} or nested shapes
      const busyCandidate =
        f?.forecastBusyPercent ??
        f?.busy ??
        f?.forecast?.forecastBusyPercent ??
        f?.value ??
        null;

      // coerce to Number and filter invalid
      const num = busyCandidate != null ? Number(busyCandidate) : null;
      if (num === null || Number.isNaN(num)) {
        // skip invalid forecasts
        continue;
      }

      datasets.push({
        nodeId,
        label: `${nodeId} (${Math.round(num)}%)`,
        value: Math.round(num),
        raw: f
      });
    }

    this.nodeForecastDatasets = datasets;
    console.log('[ForecastChart] built datasets count=', datasets.length, 'example=', datasets[0] ?? null);
  }
}
