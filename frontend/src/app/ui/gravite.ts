import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export const GRAVITES = ['CRITIQUE', 'HAUTE', 'MOYENNE', 'BASSE'] as const;
export const GRAVITE_LABEL: Record<string, string> = {
  CRITIQUE: 'Critique', HAUTE: 'Haute', MOYENNE: 'Moyenne', BASSE: 'Basse',
};
export const GRAVITE_COLOR: Record<string, string> = {
  CRITIQUE: 'var(--critical)', HAUTE: 'var(--serious)', MOYENNE: 'var(--warning)', BASSE: 'var(--neutral)',
};

/** Gravité : pastille de couleur + libellé (jamais la couleur seule). */
@Component({
  selector: 'app-gravite',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (value(); as v) {
      <span [class]="'sev ' + v.toLowerCase()"><i aria-hidden="true"></i>{{ label(v) }}</span>
    } @else {
      <span class="muted">—</span>
    }
  `,
})
export class GraviteBadge {
  readonly value = input<string | null>(null);
  protected label(v: string): string {
    return GRAVITE_LABEL[v] ?? v;
  }
}
