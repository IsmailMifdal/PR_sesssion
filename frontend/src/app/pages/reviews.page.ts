import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { ReviewSummary } from '../models';
import { SessionService } from '../session.service';
import { GRAVITES, GRAVITE_LABEL, GraviteBadge } from '../ui/gravite';
import { Icon } from '../ui/icon';

@Component({
  selector: 'app-reviews',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe, DecimalPipe, GraviteBadge, Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Revues</h1><p>Chaque pull request analysée, avec son verdict et son coût.</p></div>
      </div>

      <div class="toolbar">
        <div class="chips" role="group" aria-label="Filtrer par gravité">
          <button type="button" class="chip" [attr.aria-pressed]="gravite() === ''" (click)="gravite.set('')">Toutes</button>
          @for (g of gravites; track g) {
            <button type="button" class="chip" [attr.aria-pressed]="gravite() === g" (click)="gravite.set(g)">
              <span [class]="'sev ' + g.toLowerCase()"><i aria-hidden="true"></i></span>{{ label(g) }}</button>
          }
        </div>
        <label class="search">
          <app-icon name="filter" [size]="16" />
          <span class="sr">Filtrer par dépôt</span>
          <input class="input" placeholder="propriétaire/dépôt" [value]="depot()" (input)="onDepot($event)" />
        </label>
      </div>

      <section class="card">
        @if (reviews.error()) {
          <p class="empty error" role="alert">Chargement impossible ({{ status() }}).</p>
        } @else if (reviews.isLoading() && !reviews.hasValue()) {
          <div class="card-body"><div class="skeleton" style="height: 220px"></div></div>
        } @else if (reviews.value()?.length === 0) {
          <div class="empty">
            <p><strong>Aucune revue pour ces filtres.</strong></p>
            <p>Les revues apparaissent ici dès qu'une pull request est ouverte ou mise à jour.</p>
          </div>
        } @else {
          <div class="table-wrap">
            <table>
              <thead>
                <tr><th>Pull request</th><th>Gravité max.</th><th>Check</th><th class="num">Vrais</th>
                  <th class="num">À vérifier</th><th class="num">Écartées</th><th class="num">Durée</th><th class="num">Coût</th><th>Date</th></tr>
              </thead>
              <tbody>
                @for (r of reviews.value(); track r.id) {
                  <tr class="link" (click)="open(r.id)">
                    <td><a [routerLink]="['/console/revues', r.id]" (click)="$event.stopPropagation()"><strong>{{ r.depot }}</strong> #{{ r.pr }}</a>
                      <div class="muted mono">{{ r.commit.slice(0, 10) }}</div></td>
                    <td><app-gravite [value]="r.graviteMax" /></td>
                    <td>@if (r.bloquante) { <span class="status fail">Bloquante</span> } @else { <span class="status ok">OK</span> }</td>
                    <td class="num">{{ r.vraisProblemes }}</td>
                    <td class="num">{{ r.aVerifier }}</td>
                    <td class="num">{{ r.fauxPositifs }}</td>
                    <td class="num">{{ r.dureeS === null ? '—' : (r.dureeS | number: '1.0-0') + ' s' }}</td>
                    <td class="num">{{ r.coutUsd | number: '1.4-4' }} $</td>
                    <td class="muted nowrap">{{ r.debut | date: 'd MMM, HH:mm' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .toolbar { display: flex; justify-content: space-between; align-items: center; gap: .75rem; flex-wrap: wrap; }
    .search { position: relative; display: flex; align-items: center; width: min(280px, 100%); }
    .search app-icon { position: absolute; left: .7rem; color: var(--muted); }
    .search .input { padding-left: 2.1rem; }
    .chip .sev { gap: 0; }
    .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
    .nowrap { white-space: nowrap; }
    td a { color: var(--text); }
  `,
})
export class ReviewsPage {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly gravites = GRAVITES;
  protected readonly gravite = signal('');
  protected readonly depot = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected readonly reviews = httpResource<ReviewSummary[]>(() => {
    const params: Record<string, string> = { limit: '100' };
    if (this.gravite()) params['gravite'] = this.gravite();
    if (this.depot().trim()) params['depot'] = this.depot().trim();
    return { url: `${this.session.apiUrl()}/api/reviews`, params };
  });
  protected readonly status = computed(() => (this.reviews.error() as { status?: number } | undefined)?.status ?? '?');

  protected label(g: string): string {
    return GRAVITE_LABEL[g] ?? g;
  }

  protected onDepot(e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.depot.set(v), 300);
  }

  protected open(id: number): void {
    void this.router.navigate(['/console/revues', id]);
  }
}
