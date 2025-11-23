// src/app/pipes/keys.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';

/**
 * Returns Object.keys(value) — impure so it reacts to mutations on the object.
 * Use sparingly; prefer explicitly-computed arrays when performance matters.
 */
@Pipe({ name: 'keys', standalone: true, pure: false })
export class KeysPipe implements PipeTransform {
  transform(value: any): string[] {
    return value ? Object.keys(value) : [];
  }
}
