import { Component, input } from '@angular/core';

@Component({
  selector: 'app-gravite',
  template: `
    @if (value()) {
      <span class="badge" [class]="'badge ' + (value() ?? '').toLowerCase()">{{ value() }}</span>
    } @else {
      <span class="muted">—</span>
    }
  `,
})
export class GraviteBadge {
  readonly value = input<string | null>(null);
}
