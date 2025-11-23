import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-command-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './command-history.component.html',
  styleUrls: ['./command-history.component.css']
})
export class CommandHistoryComponent {
  @Input() commands: any[] = [];
}
