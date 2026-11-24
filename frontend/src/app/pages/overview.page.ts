import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Metrics, ReviewSummary } from '../models';
import { SessionService } from '../session.service';
import { GRAVITES, GRAVITE_COLOR, GRAVITE_LABEL, GraviteBadge } from '../ui/gravite';
import { Icon } from '../ui/icon';

@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, DatePipe, RouterLink, GraviteBadge, Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Vue d'ensemble</h1><p>Activité de PR Sentinel sur vos dépôts.</p></div>
        <a class="btn" routerLink="/console/revues">Toutes les revues <app-icon name="arrowRight" [size]="16" /></a>
      </div>

      @if (metrics.error()) {
        <div class="card card-body error" role="alert">Chargement impossible. Vérifiez l'API et le jeton.</div>
      } @else if (metrics.value(); as m) {
        @if (m.revues === 0) {
          <section class="card welcome">
            <div>
              <h2>Bienvenue sur PR Sentinel</h2>
              <p class="text-2">Installez l'app, choisissez vos dépôts et ouvrez une pull request de démonstration :
                le premier verdict arrive en une dizaine de secondes.</p>
            </div>
            <div class="w-actions">
              <a class="btn primary" routerLink="/console/demarrage">Commencer <app-icon name="arrowRight" [size]="16" /></a>
              <a class="btn" routerLink="/console/revues/demo">Voir un exemple</a>
            </div>
          </section>
        }
        <div class="grid tiles">
          <div class="card tile"><span class="label">Revues</span><span class="value">{{ m.revues }}</span>
            <span class="hint">{{ m.alertes }} alertes analysées</span></div>
          <div class="card tile"><span class="label">Revues bloquantes</span><span class="value">{{ m.revues_bloquantes }}</span>
            <span class="hint">fusion bloquée par le check</span></div>
          <div class="card tile"><span class="label">Fausses alertes écartées</span><span class="value">{{ m.faux_positifs }}</span>
            <span class="hint">{{ share(m.faux_positifs, m.alertes) }} des alertes</span></div>
          <div class="card tile"><span class="label">Coût moyen</span>
            <span class="value">{{ m.cout_moyen_usd | number: '1.3-3' }} $</span>
            <span class="hint">{{ m.cout_total_usd | number: '1.2-2' }} $ au total</span></div>
          <div class="card tile"><span class="label">Durée moyenne</span>
            <span class="value">{{ m.duree_moyenne_s === null ? '—' : (m.duree_moyenne_s | number: '1.0-0') + ' s' }}</span>
            <span class="hint">webhook → revue publiée</span></div>
        </div>

        <div class="grid two">
          <section class="card">
            <div class="card-head"><h2>Gravité des vrais problèmes</h2>
              <span class="muted small">{{ sevTotal() }} au total</span></div>
            <div class="card-body sev-body">
              @if (sevTotal() > 0) {
                <div class="tallbar" role="img" [attr.aria-label]="sevAria()">
                  @for (s of sevSegments(); track s.key) {
                    @if (s.count > 0) {
                      <span [style.flex-grow]="s.count" [style.background]="s.color">
                        @if (s.count / sevTotal() > 0.12) { <b>{{ s.count }}</b> }
                      </span>
                    }
                  }
                </div>
                <ul class="legend">
                  @for (s of sevSegments(); track s.key) {
                    <li><span [class]="'sev ' + s.key.toLowerCase()"><i aria-hidden="true"></i>{{ s.label }}</span>
                      <span class="num">{{ s.count }}</span></li>
                  }
                </ul>
              } @else {
                <p class="empty">Aucun vrai problème pour l'instant.</p>
              }
            </div>
          </section>

          <section class="card">
            <div class="card-head"><h2>Triage des alertes</h2>
              <span class="muted small">{{ m.alertes }} alertes</span></div>
            <div class="card-body triage">
              @for (t of triage(m); track t.label) {
                <div class="t-row">
                  <div class="t-top"><span>{{ t.label }}</span><span class="num"><strong>{{ t.count }}</strong>
                    <span class="muted"> · {{ share(t.count, m.alertes) }}</span></span></div>
                  <div class="meter"><span [style.width.%]="pct(t.count, m.alertes)"></span></div>
                  <p class="muted small">{{ t.hint }}</p>
                </div>
              }
            </div>
          </section>
        </div>

        <section class="card">
          <div class="card-head"><h2>Revues récentes</h2>
            <a routerLink="/console/revues" class="small">Tout voir</a></div>
          @if (recent.value(); as list) {
            @if (list.length === 0) {
              <div class="empty">
                <p><strong>Aucune revue pour l'instant.</strong></p>
                <p>Ouvrez une pull request sur un dépôt où la GitHub App est installée.</p>
              </div>
            } @else {
              <ul class="recent">
                @for (r of list; track r.id) {
                  <li>
                    <a [routerLink]="['/console/revues', r.id]">
                      <span class="pr"><strong>{{ r.depot }}</strong> <span class="muted">#{{ r.pr }}</span></span>
                      <app-gravite [value]="r.graviteMax" />
                      <span class="counts muted small">{{ r.vraisProblemes }} vrai(s) · {{ r.fauxPositifs }} écartée(s)</span>
                      @if (r.bloquante) { <span class="status fail">Bloquante</span> } @else { <span class="status ok">OK</span> }
                      <span class="muted small when">{{ r.debut | date: 'd MMM, HH:mm' }}</span>
                    </a>
                  </li>
                }
              </ul>
            }
          } @else {
            <div class="card-body"><div class="skeleton" style="height: 120px"></div></div>
          }
        </section>
      } @else {
        <div class="grid tiles">@for (i of [1, 2, 3, 4, 5]; track i) { <div class="card skeleton" style="height: 104px"></div> }</div>
      }
    </div>
  `,
  styles: `
    .small { font-size: .8rem; }
    .welcome { padding: 1.3rem 1.4rem; display: flex; justify-content: space-between; align-items: center; gap: 1rem 2rem;
      flex-wrap: wrap; background: linear-gradient(120deg, var(--accent-soft), var(--surface) 70%); }
    .welcome h2 { margin-bottom: .3rem; }
    .welcome p { max-width: 38rem; }
    .w-actions { display: flex; gap: .5rem; flex-wrap: wrap; }
    .sev-body { display: grid; gap: 1.1rem; }
    .tallbar { display: flex; gap: 2px; height: 28px; border-radius: 6px; overflow: hidden; }
    .tallbar span { flex-basis: 0; min-width: 6px; display: grid; place-items: center; }
    .tallbar b { font-size: .75rem; font-weight: 650; color: #0b0b0b; }
    .legend { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(2, 1fr); gap: .55rem 1.5rem; }
    .legend li { display: flex; justify-content: space-between; align-items: center; font-size: .88rem; }
    .triage { display: grid; gap: 1rem; }
    .t-row { display: grid; gap: .35rem; }
    .t-top { display: flex; justify-content: space-between; font-size: .9rem; }
    .recent { list-style: none; padding: 0; margin: 0; }
    .recent li + li { border-top: 1px solid var(--border); }
    .recent a { display: grid; grid-template-columns: minmax(0, 1.6fr) 110px minmax(0, 1fr) 100px 110px; align-items: center; gap: 1rem;
      padding: .8rem 1.2rem; color: var(--text); text-decoration: none; }
    .recent a:hover { background: var(--surface-sunken); }
    .pr .muted { margin-left: .3rem; }
    .pr { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .when { text-align: right; }
    @media (max-width: 760px) {
      .recent a { grid-template-columns: 1fr auto; gap: .35rem .75rem; }
      .counts, .when { display: none; }
    }
  `,
})
export class OverviewPage {
  private readonly session = inject(SessionService);
  protected readonly metrics = httpResource<Metrics>(() => `${this.session.apiUrl()}/api/metrics`);
  protected readonly recent = httpResource<ReviewSummary[]>(() => ({
    url: `${this.session.apiUrl()}/api/reviews`, params: { limit: '6' },
  }));

  protected readonly sevSegments = computed(() => {
    const by = this.metrics.value()?.par_gravite ?? {};
    return GRAVITES.map((k) => ({ key: k, label: GRAVITE_LABEL[k], color: GRAVITE_COLOR[k], count: by[k] ?? 0 }));
  });
  protected readonly sevTotal = computed(() => this.sevSegments().reduce((a, s) => a + s.count, 0));
  protected readonly sevAria = computed(() =>
    'Répartition par gravité : ' + this.sevSegments().map((s) => `${s.label} ${s.count}`).join(', '));

  protected triage(m: Metrics) {
    return [
      { label: 'Vrais problèmes', count: m.vrais_problemes, hint: 'Commentés sur la PR, avec correctif.' },
      { label: 'À vérifier', count: m.a_verifier, hint: "L'agent manque de contexte : un humain tranche." },
      { label: 'Fausses alertes écartées', count: m.faux_positifs, hint: 'Résumées dans la revue, sans commentaire en ligne.' },
    ];
  }

  protected pct(n: number, total: number): number {
    return total > 0 ? Math.round((n / total) * 1000) / 10 : 0;
  }

  protected share(n: number, total: number): string {
    return total > 0 ? `${Math.round((n / total) * 100)} %` : '—';
  }
}
