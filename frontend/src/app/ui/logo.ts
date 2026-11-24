import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="mark" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3l7.5 3v5.5c0 4.6-3.2 8.3-7.5 9.5-4.3-1.2-7.5-4.9-7.5-9.5V6z" />
        <path d="M8.8 12.2l2.2 2.2 4.2-4.6" />
      </svg>
    </span>
    <span class="name">PR Sentinel</span>
  `,
  styles: `
    :host { display: inline-flex; align-items: center; gap: .55rem; font-weight: 650; letter-spacing: -0.01em; color: var(--text); }
    .mark { width: 30px; height: 30px; border-radius: 8px; display: grid; place-items: center;
      background: var(--accent); color: var(--on-accent); }
  `,
})
export class Logo {}
