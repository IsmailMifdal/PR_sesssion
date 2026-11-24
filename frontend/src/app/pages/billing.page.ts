import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource, HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ApiService } from '../api.service';
import { Billing, BillingAccount } from '../models';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';

const STATUT: Record<string, string> = {
  trialing: "Période d'essai", active: 'Actif', past_due: 'Paiement en retard', canceled: 'Résilié',
  unpaid: 'Impayé', incomplete: 'Paiement à finaliser', incomplete_expired: 'Paiement expiré', paused: 'En pause',
};

/** Seules des pages Stripe peuvent recevoir le navigateur (défense contre une URL inattendue). */
export function isStripeUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && (u.hostname === 'checkout.stripe.com' || u.hostname === 'billing.stripe.com');
  } catch {
    return false;
  }
}

@Component({
  selector: 'app-billing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Facturation</h1><p>Offre et consommation de chaque compte GitHub où PR Sentinel est installé.</p></div>
      </div>

      @if (statut() === 'ok') {
        <div class="notice good" role="status"><app-icon name="check" /> Paiement confirmé. L'offre Équipe s'active
          dans quelques secondes.</div>
      } @else if (statut() === 'annule') {
        <div class="notice" role="status">Paiement annulé : rien n'a été facturé.</div>
      }
      @if (error()) { <div class="notice bad" role="alert">{{ error() }}</div> }

      @if (billing.error()) {
        <div class="card empty error" role="alert">Chargement impossible.</div>
      } @else if (billing.value(); as b) {
        @if (!b.actif) {
          <div class="card empty">
            <p><strong>Facturation désactivée sur cette instance.</strong></p>
            <p>Instance auto-hébergée ou Stripe non configuré : toutes les revues sont illimitées.</p>
          </div>
        } @else if (b.comptes.length === 0) {
          <div class="card empty">
            <p><strong>Aucun compte à gérer.</strong></p>
            <p>Installez la GitHub App PR Sentinel sur votre compte ou votre organisation, puis reconnectez-vous.</p>
          </div>
        } @else {
          @for (c of b.comptes; track c.installationId) {
            <section class="card account">
              <div class="card-head">
                <div class="acc-title">
                  <h2>{{ c.compte }}</h2>
                  <span class="plan" [class.paid]="c.offre === 'EQUIPE'">{{ c.offre === 'EQUIPE' ? 'Équipe' : 'Découverte' }}</span>
                  @if (c.statut && c.offre === 'EQUIPE') { <span class="muted small">{{ statutLabel(c.statut) }}</span> }
                </div>
              </div>
              <div class="card-body acc-body">
                @if (c.offre === 'EQUIPE') {
                  <dl class="facts">
                    <div><dt>Sièges</dt><dd>{{ c.sieges }}</dd></div>
                    <div><dt>Revues ce mois-ci</dt><dd>{{ c.revuesCeMois }} <span class="muted small">illimitées</span></dd></div>
                    @if (c.finPeriode) {
                      <div><dt>{{ periodLabel(c) }}</dt><dd>{{ c.finPeriode | date: 'd MMMM y' }}</dd></div>
                    }
                  </dl>
                  @if (c.statut === 'past_due') {
                    <p class="warn"><app-icon name="alert" [size]="16" /> Le dernier paiement a échoué. Mettez à jour votre
                      moyen de paiement pour garder l'offre Équipe.</p>
                  }
                  <div class="actions">
                    <button type="button" class="btn" [disabled]="busy() === c.installationId" (click)="portal(c)">
                      <app-icon name="card" [size]="16" /> Gérer l'abonnement</button>
                    <span class="muted small">Factures, moyen de paiement, sièges, résiliation.</span>
                  </div>
                } @else {
                  <div class="usage">
                    <div class="u-top"><span>Revues ce mois-ci</span>
                      <strong class="num">{{ c.revuesCeMois }} / {{ c.quota }}</strong></div>
                    <div class="meter" [class.full]="full(c)" role="img"
                         [attr.aria-label]="c.revuesCeMois + ' revues sur ' + c.quota">
                      <span [style.width.%]="usagePct(c)"></span></div>
                    @if (full(c)) {
                      <p class="warn"><app-icon name="alert" [size]="16" /> Quota atteint : les nouvelles pull requests ne
                        sont plus analysées jusqu'au 1er du mois prochain.</p>
                    } @else {
                      <p class="muted small">Remis à zéro le 1er de chaque mois.</p>
                    }
                  </div>
                  <div class="upgrade">
                    <div>
                      <h3>Passer à l'offre Équipe</h3>
                      <p class="text-2 small">Revues illimitées, facturées par développeur.
                        @if (c.essaiDisponible) { {{ b.joursEssai }} jours d'essai gratuit, sans engagement. }</p>
                    </div>
                    <div class="buy">
                      <label class="seats">
                        <span>Développeurs</span>
                        <input class="input" type="number" min="1" max="500" [value]="seats(c)"
                               (input)="setSeats(c, $event)" />
                      </label>
                      <button type="button" class="btn primary" [disabled]="busy() === c.installationId" (click)="checkout(c)">
                        {{ c.essaiDisponible ? "Démarrer l'essai" : "S'abonner" }} <app-icon name="arrowRight" [size]="16" /></button>
                    </div>
                  </div>
                  @if (c.gerable) {
                    <button type="button" class="btn ghost small-btn" (click)="portal(c)">Voir les factures passées</button>
                  }
                }
              </div>
            </section>
          }

          <section class="card compare">
            <div class="card-head"><h2>Comparer les offres</h2></div>
            <div class="table-wrap">
              <table>
                <thead><tr><th></th><th>Découverte</th><th>Équipe</th></tr></thead>
                <tbody>
                  <tr><td>Revues par mois</td><td>{{ b.quotaDecouverte }}</td><td>Illimitées</td></tr>
                  <tr><td>Semgrep, Gitleaks, Trivy + triage IA</td><td>✓</td><td>✓</td></tr>
                  <tr><td>Commentaires, correctifs, check run bloquant</td><td>✓</td><td>✓</td></tr>
                  <tr><td>Dépôts</td><td>Illimités</td><td>Illimités</td></tr>
                  <tr><td>Paiement</td><td>Gratuit</td><td>Par développeur, sans engagement</td></tr>
                </tbody>
              </table>
            </div>
          </section>
          <p class="muted small">Paiement sécurisé par Stripe : PR Sentinel ne voit ni ne stocke vos données de carte.</p>
        }
      } @else {
        <div class="skeleton" style="height: 220px"></div>
      }
    </div>
  `,
  styles: `
    .small { font-size: .8rem; }
    .notice { padding: .8rem 1rem; border-radius: var(--radius); border: 1px solid var(--border); background: var(--surface);
      display: flex; gap: .5rem; align-items: center; font-size: .9rem; }
    .notice.good { background: var(--good-soft); border-color: transparent; }
    .notice.good app-icon { color: var(--good); }
    .notice.bad { background: var(--critical-soft); border-color: transparent; }
    .acc-title { display: flex; align-items: center; gap: .65rem; flex-wrap: wrap; }
    .plan { font-size: .75rem; font-weight: 600; padding: .15rem .55rem; border-radius: 999px; background: var(--surface-sunken);
      border: 1px solid var(--border); }
    .plan.paid { background: var(--accent-soft); border-color: transparent; color: var(--text); }
    .acc-body { display: grid; gap: 1.2rem; }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 1rem; margin: 0; }
    .facts dt { font-size: .8rem; color: var(--text-2); }
    .facts dd { margin: .2rem 0 0; font-size: 1.2rem; font-weight: 620; }
    .actions { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
    .usage { display: grid; gap: .45rem; max-width: 520px; }
    .u-top { display: flex; justify-content: space-between; font-size: .9rem; }
    .meter.full span { background: var(--critical); }
    .warn { display: flex; gap: .45rem; align-items: center; font-size: .86rem; }
    .warn app-icon { color: var(--serious); }
    .upgrade { display: flex; justify-content: space-between; align-items: flex-end; gap: 1rem 2rem; flex-wrap: wrap;
      padding: 1.1rem; border-radius: var(--radius); background: var(--accent-soft); }
    .upgrade h3 { margin-bottom: .25rem; }
    .buy { display: flex; align-items: flex-end; gap: .75rem; }
    .seats { display: grid; gap: .3rem; font-size: .78rem; color: var(--text-2); }
    .seats .input { width: 96px; }
    .small-btn { width: fit-content; height: 32px; font-size: .84rem; padding: 0 .5rem; }
    td:first-child { color: var(--text-2); }
  `,
})
export class BillingPage {
  private readonly session = inject(SessionService);
  private readonly api = inject(ApiService);
  /** Retour de Stripe Checkout : {@code ok} ou {@code annule}. */
  readonly statutParam = input<string>(undefined, { alias: 'statut' });
  protected readonly statut = computed(() => this.statutParam());
  protected readonly billing = httpResource<Billing>(() => `${this.session.apiUrl()}/api/billing`);
  protected readonly busy = signal<number | null>(null);
  protected readonly error = signal('');
  private readonly seatsByAccount = signal<Record<number, number>>({});

  constructor() {
    // L'activation arrive par webhook, quelques secondes après le retour de Stripe : on relit une fois.
    setTimeout(() => {
      if (this.statut() === 'ok') this.billing.reload();
    }, 4000);
  }

  protected statutLabel(s: string): string {
    return STATUT[s] ?? s;
  }

  protected periodLabel(c: BillingAccount): string {
    if (c.statut === 'trialing') return "Fin de l'essai";
    return c.annulationFinPeriode ? 'Fin de l’abonnement' : 'Prochaine facture';
  }

  protected usagePct(c: BillingAccount): number {
    return c.quota ? Math.min(100, (c.revuesCeMois / c.quota) * 100) : 0;
  }

  protected full(c: BillingAccount): boolean {
    return c.quota !== null && c.revuesCeMois >= c.quota;
  }

  protected seats(c: BillingAccount): number {
    return this.seatsByAccount()[c.installationId] ?? 1;
  }

  protected setSeats(c: BillingAccount, e: Event): void {
    const n = Math.round(Number((e.target as HTMLInputElement).value));
    this.seatsByAccount.update((m) => ({ ...m, [c.installationId]: Math.min(500, Math.max(1, n || 1)) }));
  }

  protected checkout(c: BillingAccount): void {
    this.go(c, this.api.checkout(c.installationId, this.seats(c)));
  }

  protected portal(c: BillingAccount): void {
    this.go(c, this.api.portal(c.installationId));
  }

  private go(c: BillingAccount, call: ReturnType<ApiService['portal']>): void {
    this.error.set('');
    this.busy.set(c.installationId);
    call.subscribe({
      next: (r) => {
        if (isStripeUrl(r.url)) {
          location.assign(r.url);
        } else {
          this.busy.set(null);
          this.error.set('Adresse de paiement inattendue : opération annulée.');
        }
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(null);
        this.error.set(e.status === 409 ? "Ce compte est déjà abonné : utilisez « Gérer l'abonnement »."
          : e.status === 403 ? "Vous n'avez pas accès à ce compte GitHub."
          : 'Stripe est momentanément indisponible. Réessayez dans un instant.');
        this.billing.reload();
      },
    });
  }
}
