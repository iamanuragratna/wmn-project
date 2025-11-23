import { Component, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-admin-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-panel.component.html',
  styleUrls: ['./admin-panel.component.css']
})
export class AdminPanelComponent {
  nodeId = '';
  channel = 6;
  @Output() send = new EventEmitter<{nodeId:string, channel:number}>();

  doSend(){
    if(!this.nodeId) return;
    this.send.emit({nodeId: this.nodeId, channel: Number(this.channel)});
    this.nodeId = '';
  }
}
