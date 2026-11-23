import { Component, computed, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ReviewSummary } from '../models';
import { SessionService } from '../session.service';
import { GraviteBadge } from './gravite-badge';

@Component({
  selector: 'app-reviews',
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, GraviteBadge],
  template: `
    <h1>Revues</h1>
    <form class="filters" (submit)="$event.preventDefault()">
      <label>Gravité
        <select name="gravite" [ngModel]="gravite()" (ngModelChange)="gravite.set($event)">
          <option value="">Toutes</option>
          @for (g of gravites; track g) { <option [value]="g">{{ g }}</option> }
        </select>
      </label>
      <label>Dépôt
        <input name="depot" placeholder="propriétaire/dépôt" [ngModel]="depot()" (ngModelChange)="depot.set($event)" />
      </label>
    </form>

    @if (reviews.error()) {
      <p class="error" role="alert">Chargement impossible ({{ status() }}).</p>
    } @else if (reviews.isLoading()) {
      <p class="muted">Chargement…</p>
    } @else if (reviews.value()?.length === 0) {
      <p class="muted">Aucune revue pour ces filtres.</p>
    } @else {
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>PR</th><th>Gravité</th><th>Vrais</th><th>À vérifier</th><th>Faux positifs</th>
              <th>Durée</th><th>Coût</th><th>Date</th></tr>
          </thead>
          <tbody>
            @for (r of reviews.value(); track r.id) {
              <tr [class.blocking]="r.bloquante">
                <td><a [routerLink]="['/revues', r.id]">{{ r.depot }} #{{ r.pr }}</a>
                  <div class="muted mono">{{ r.commit.slice(0, 12) }}</div></td>
                <td><app-gravite [value]="r.graviteMax" />@if (r.bloquante) { <span class="tag">bloquante</span> }</td>
                <td>{{ r.vraisProblemes }}</td>
                <td>{{ r.aVerifier }}</td>
                <td>{{ r.fauxPositifs }}</td>
                <td>{{ r.dureeS === null ? '—' : (r.dureeS | number: '1.0-0') + ' s' }}</td>
                <td>{{ r.coutUsd | number: '1.4-4' }} $</td>
                <td>{{ r.debut | date: 'short' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class ReviewsPage {
  private readonly session = inject(SessionService);
  protected readonly gravites = ['CRITIQUE', 'HAUTE', 'MOYENNE', 'BASSE'];
  protected readonly gravite = signal('');
  protected readonly depot = signal('');

  protected readonly reviews = httpResource<ReviewSummary[]>(() => {
    const params: Record<string, string> = { limit: '100' };
    if (this.gravite()) params['gravite'] = this.gravite();
    if (this.depot().trim()) params['depot'] = this.depot().trim();
    return { url: `${this.session.apiUrl()}/api/reviews`, params };
  });
  protected readonly status = computed(() => (this.reviews.error() as { status?: number } | undefined)?.status ?? '?');
}
