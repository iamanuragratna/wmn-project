import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-alerts',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alerts.component.html',
  styleUrls: ['./alerts.component.css']
})
export class AlertsComponent {
  @Input() telemetry: any = {};
  getAlerts(){
    const alerts:any[] = [];
    Object.keys(this.telemetry || {}).forEach(id=>{
      const arr = this.telemetry[id] || [];
      const last = arr[arr.length-1];
      if(last && last.busy > 90) alerts.push({id, msg: 'High congestion: '+ last.busy});
    });
    return alerts;
  }
}
