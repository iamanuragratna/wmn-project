// src/app/app.component.ts
import { Component, OnInit, OnDestroy } from '@angular/core';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { WebsocketService } from './services/websocket.service';

// standalone child components / pipes (these must match your files)
import { NodeListComponent } from './components/node-list/node-list.component';
import { TelemetryChartComponent } from './components/telemetry-chart/telemetry-chart.component';
import { ForecastChartComponent } from './components/forecast-chart/forecast-chart.component';
import { PlanViewComponent } from './components/plan-view/plan-view.component';
import { CommandHistoryComponent } from './components/command-history/command-history.component';
import { AdminPanelComponent } from './components/admin-panel/admin-panel.component';
import { AlertsComponent } from './components/alerts/alerts.component';
import { ObservabilityComponent } from './components/observability/observability.component';
import { KeysPipe } from './pipes/keys.pipe';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    HttpClientModule,
    FormsModule,
    // child standalone components / pipes
    NodeListComponent,
    TelemetryChartComponent,
    ForecastChartComponent,
    PlanViewComponent,
    CommandHistoryComponent,
    AdminPanelComponent,
    AlertsComponent,
    ObservabilityComponent,
    KeysPipe
  ],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  nodes: any = {};
  telemetry: { [nodeId: string]: any[] } = {};
  forecasts: { [nodeId: string]: any } = {};
  plans: any[] = [];
  commands: any[] = [];
  logs: string[] = [];

  private sub?: Subscription;

  constructor(private ws: WebsocketService, private http: HttpClient) {}

  ngOnInit(){
    this.sub = this.ws.messages$.subscribe(msg => {
      try {
        const { type, payload } = msg;

        if (type === 'feature_update') {
          const nodeId = payload.nodeId;
          this.logs.push(`feature_update ${nodeId}`);

          // build a new telemetry array for this node (immutable update)
          const nodeArr = (this.telemetry[nodeId] || []).concat([{
            ts: payload.windowEnd,
            busy: payload.avgChannelBusyPercent,
            rssi: payload.avgRssi,
            txBytes: payload.sumTxBytes
          }]);

          // keep array length sane
          const trimmed = nodeArr.length > 200 ? nodeArr.slice(nodeArr.length - 200) : nodeArr;

          // immutable update of telemetry object so child @Input sees new reference
          this.telemetry = { ...this.telemetry, [nodeId]: trimmed };

          // update nodes summary (immutable)
          const updatedNode = {
            ...(this.nodes[nodeId] || {}),
            lastSeen: payload.lastSeen,
            lastRssi: payload.avgRssi,
            channelBusy: payload.avgChannelBusyPercent,
            clients: payload.sampleCount
          };
          this.nodes = { ...this.nodes, [nodeId]: updatedNode };

        } else if (type === 'forecast_update') {
          const nodeId = payload.nodeId;
          this.logs.push(`forecast_update ${nodeId}`);

          // immutable update of forecasts map
          this.forecasts = { ...this.forecasts, [nodeId]: payload };

        } else if (type === 'optimizer_plan') {
          const nodeId = payload.nodeId;
          this.logs.push(`optimizer_plan ${nodeId}`);

          // keep newest at front
          const newPlan = { ...payload, status: 'pending', ts: new Date().toISOString() };
          this.plans = [newPlan, ...this.plans].slice(0, 200);

          // keep a node suggestedChannel copy too (optional)
          if (payload.channel) {
            this.nodes = { ...this.nodes, [nodeId]: { ...(this.nodes[nodeId] || {}), suggestedChannel: payload.channel } };
          }

        } else if (type === 'command_status') {
          const nodeId = payload.nodeId;
          this.logs.push(`command_status ${nodeId}`);

          const newCmd = { ...payload, ts: new Date().toISOString() };
          this.commands = [newCmd, ...this.commands].slice(0, 200);
        }
      } catch(e){
        console.error(e);
      }
    });

    this.ws.connect();
  }

  ngOnDestroy(){
    if(this.sub) this.sub.unsubscribe();
    this.ws.disconnect();
  }

  sendManualCommand(nodeId: string, channel: number){
    const body = { nodeId, command: 'SET_CHANNEL', payload: String(channel), triggeredBy: 'manual' };
    this.http.post('/api/controller/commands', body).subscribe({
      next: () => this.logs.push(`Manual command sent ${nodeId}->${channel}`),
      error: (err) => {
        this.logs.push('Manual command failed');
        console.error('[AppComponent] manual command error', err);
      }
    });
  }
}
