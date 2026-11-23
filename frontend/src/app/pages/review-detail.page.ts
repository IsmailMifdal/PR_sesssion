import { Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ReviewDetail } from '../models';
import { SessionService } from '../session.service';
import { GraviteBadge } from './gravite-badge';

@Component({
  selector: 'app-review-detail',
  imports: [RouterLink, DecimalPipe, PercentPipe, GraviteBadge],
  template: `
    <p><a routerLink="/revues">← Revues</a></p>
    @if (detail.error()) {
      <p class="error" role="alert">Revue introuvable.</p>
    } @else if (detail.value(); as d) {
      <h1>{{ d.revue.depot }} #{{ d.revue.pr }}</h1>
      <p class="muted mono">{{ d.revue.commit }} · {{ d.revue.statut }} · {{ d.revue.tokens }} tokens ·
        {{ d.revue.coutUsd | number: '1.4-4' }} $</p>
      @if (d.revue.bloquante) {
        <p class="banner">⛔ Faille critique : le check run bloque la fusion.</p>
      }
      <div class="filters">
        @for (f of filters; track f.value) {
          <button type="button" [class.selected]="filter() === f.value" (click)="filter.set(f.value)">{{ f.label }}</button>
        }
      </div>
      @for (a of visible(); track a.id) {
        <article class="card finding">
          <header>
            <app-gravite [value]="a.verdict?.gravite ?? null" />
            <strong>{{ decisionLabel(a.verdict?.decision) }}</strong>
            <span class="mono">{{ a.fichier }}{{ a.ligne ? ':' + a.ligne : '' }}</span>
          </header>
          <p class="muted"><span class="mono">{{ a.outil }} · {{ a.regle }}</span> · sévérité outil {{ a.graviteOutil }}</p>
          @if (a.verdict; as v) {
            <p>{{ v.explication }}</p>
            @if (v.correctif) {
              <details open><summary>Correctif proposé</summary><pre><code>{{ v.correctif }}</code></pre></details>
            }
            <p class="muted">Confiance {{ v.confiance | percent: '1.0-0' }} · {{ v.modele }}</p>
          } @else {
            <p class="muted">{{ a.message }}</p>
            <p class="muted">Alerte non triée (agent inactif, plafond ou budget atteint).</p>
          }
        </article>
      } @empty {
        <p class="muted">Aucune alerte dans cette catégorie.</p>
      }
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class ReviewDetailPage {
  private readonly session = inject(SessionService);
  readonly id = input.required<string>();
  protected readonly filters = [
    { value: 'TOUT', label: 'Tout' },
    { value: 'CRITIQUE', label: 'Critiques' },
    { value: 'VRAI_PROBLEME', label: 'Vrais problèmes' },
    { value: 'A_VERIFIER', label: 'À vérifier' },
    { value: 'FAUX_POSITIF', label: 'Faux positifs' },
  ];
  protected readonly filter = signal('TOUT');

  protected readonly detail = httpResource<ReviewDetail>(() => {
    const id = Number(this.id());
    return Number.isInteger(id) && id > 0 ? `${this.session.apiUrl()}/api/reviews/${id}` : undefined;
  });

  protected readonly visible = computed(() => {
    const all = this.detail.value()?.alertes ?? [];
    const f = this.filter();
    if (f === 'TOUT') return all;
    if (f === 'CRITIQUE') return all.filter((a) => a.verdict?.gravite === 'CRITIQUE' && a.verdict.decision !== 'FAUX_POSITIF');
    return all.filter((a) => a.verdict?.decision === f);
  });

  decisionLabel(d: string | undefined): string {
    return d === 'VRAI_PROBLEME' ? 'Vrai problème' : d === 'A_VERIFIER' ? 'À vérifier'
      : d === 'FAUX_POSITIF' ? 'Faux positif' : 'Non triée';
  }
}

