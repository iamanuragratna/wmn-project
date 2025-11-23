import { Component, Input, Output, EventEmitter, DoCheck } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-node-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './node-list.component.html',
  styleUrls: ['./node-list.component.css']
})
export class NodeListComponent implements DoCheck {
  @Input() nodes: any = {};
  @Output() sendCommand = new EventEmitter<{nodeId:string, channel:number}>();

  // expose Object to template so templates can call Object.keys(...)
  public Object = Object;

  // derived list displayed in template (array of normalized node entries)
  public displayNodes: Array<{
    id: string,
    lastSeen?: string,
    lastRssi?: number | null,
    channelBusy?: number | null,
    clients?: number | null,
    suggestedChannel?: number | undefined,
    currentChannel?: number | undefined
  }> = [];

  selected?: string;

  // cache keys for cheap change detection
  private _lastKeysJson = '';

  ngDoCheck() {
    const keys = this.nodes ? Object.keys(this.nodes) : [];
    const json = JSON.stringify(keys);
    if (json !== this._lastKeysJson) {
      this._lastKeysJson = json;
      this.rebuildDisplayNodes();
      console.log('[NodeList] doCheck rebuilt displayNodes count=', this.displayNodes.length);
    } else {
      // minor defensive rebuild to keep numbers normalized if some children mutate in-place
      this.rebuildDisplayNodes();
    }
  }

  private rebuildDisplayNodes() {
    const out: any[] = [];
    if (!this.nodes) {
      this.displayNodes = [];
      return;
    }

    for (const id of Object.keys(this.nodes)) {
      const n = this.nodes[id] || {};

      // normalize fields (coerce numeric values when possible)
      const lastSeen = n.lastSeen ?? n.windowEnd ?? n.ts ?? n.last_seen ?? undefined;
      const lastRssi = n.lastRssi != null ? Number(n.lastRssi) : (n.avgRssi != null ? Number(n.avgRssi) : null);
      const channelBusy = n.channelBusy != null ? Number(n.channelBusy) : (n.avgChannelBusyPercent != null ? Number(n.avgChannelBusyPercent) : null);
      const clients = n.clients != null ? Number(n.clients) : (n.sampleCount != null ? Number(n.sampleCount) : null);
      const suggestedChannel = n.suggestedChannel != null ? Number(n.suggestedChannel) : undefined;
      const currentChannel = n.currentChannel != null ? Number(n.currentChannel) : undefined;

      // Filter: include only if it looks like a real node telemetry entry (has lastSeen or channelBusy or clients)
      const looksLikeNode = !!lastSeen || channelBusy !== null || clients !== null;

      if (!looksLikeNode) {
        // skip entries that appear to be non-node messages accidentally injected
        continue;
      }

      out.push({
        id,
        lastSeen,
        lastRssi,
        channelBusy,
        clients,
        suggestedChannel,
        currentChannel
      });
    }

    // sort by lastSeen (newest first) if available
    out.sort((a,b) => {
      if (a.lastSeen && b.lastSeen) return new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime();
      if (a.lastSeen && !b.lastSeen) return -1;
      if (!a.lastSeen && b.lastSeen) return 1;
      return a.id.localeCompare(b.id);
    });

    this.displayNodes = out;
  }

  onSend(nodeId:string, channel:number){
    this.sendCommand.emit({nodeId, channel});
  }
}
