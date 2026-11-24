import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../api.service';
import { Me } from '../models';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';
import { LEGAL } from './legal';

/** Mon compte : données détenues, export (accès, portabilité) et suppression (effacement). */
@Component({
  selector: 'app-account',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Mon compte</h1><p>Vos données personnelles et vos droits.</p></div>
      </div>

      @if (me.value(); as m) {
        @if (m.admin) {
          <div class="card empty">
            <p><strong>Accès opérateur.</strong></p>
            <p>Le jeton d'administration n'est pas un compte personnel : aucune donnée personnelle n'y est rattachée.</p>
          </div>
        } @else {
          <section class="card">
            <div class="card-head"><h2>Profil</h2></div>
            <div class="card-body profile">
              @if (m.avatarUrl) {
                <img [src]="m.avatarUrl" alt="" width="56" height="56" referrerpolicy="no-referrer" />
              }
              <div>
                <p><strong>{{ m.nom || m.login }}</strong> <span class="muted">&#64;{{ m.login }}</span></p>
                <p class="small text-2">Nom et avatar viennent de votre profil GitHub et se mettent à jour à chaque connexion.</p>
                <p class="small text-2">{{ m.depots.length }} dépôt(s) visibles.</p>
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-head"><h2>Ce que nous conservons</h2></div>
            <div class="card-body">
              <ul class="facts">
                <li><app-icon name="check" [size]="16" /> Le code de vos pull requests n'est jamais conservé : cloné le temps
                  de l'analyse, puis effacé.</li>
                <li><app-icon name="check" [size]="16" /> Les résultats des revues sont effacés après
                  {{ retention() }} jours, et aussitôt si l'app est désinstallée.</li>
                <li><app-icon name="check" [size]="16" /> Aucune donnée de carte bancaire : le paiement est traité par Stripe.</li>
              </ul>
              <p class="small"><a routerLink="/confidentialite">Politique de confidentialité complète</a></p>
            </div>
          </section>

          <section class="card">
            <div class="card-head"><h2>Télécharger mes données</h2></div>
            <div class="card-body row">
              <p class="text-2">Profil, dépôts et installations visibles, avis donnés sur les verdicts, au format JSON.</p>
              <button type="button" class="btn" [disabled]="busy()" (click)="download()">
                <app-icon name="arrowRight" [size]="16" /> Télécharger (JSON)</button>
            </div>
          </section>

          <section class="card danger">
            <div class="card-head"><h2>Supprimer mon compte</h2></div>
            <div class="card-body">
              <p class="text-2">Efface votre profil, vos sessions, vos liens aux dépôts et vos avis. Les revues appartiennent au
                compte GitHub qui a installé l'app : pour les effacer, désinstallez l'app sur GitHub. Un abonnement en cours
                se résilie depuis la page Facturation.</p>
              <div class="confirm">
                <label for="confirm" class="small text-2">Pour confirmer, saisissez votre login GitHub
                  (<strong>{{ m.login }}</strong>)</label>
                <div class="row">
                  <input id="confirm" class="input" autocomplete="off" [value]="typed()" (input)="typed.set(value($event))" />
                  <button type="button" class="btn delete" [disabled]="typed() !== m.login || busy()" (click)="remove()">
                    Supprimer définitivement</button>
                </div>
              </div>
            </div>
          </section>
        }
      } @else if (me.error()) {
        <div class="card empty error" role="alert">Chargement impossible.</div>
      } @else {
        <div class="skeleton" style="height: 240px"></div>
      }
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    </div>
  `,
  styles: `
    .small { font-size: .82rem; }
    .profile { display: flex; gap: 1rem; align-items: center; }
    .profile img { border-radius: 50%; }
    .profile .muted { margin-left: .35rem; }
    .facts { list-style: none; padding: 0; margin: 0 0 .8rem; display: grid; gap: .5rem; }
    .facts li { display: flex; gap: .5rem; align-items: flex-start; }
    .facts app-icon { color: var(--good); margin-top: .15rem; }
    .row { display: flex; gap: .75rem 1rem; align-items: center; justify-content: space-between; flex-wrap: wrap; }
    .confirm { display: grid; gap: .4rem; margin-top: 1rem; max-width: 520px; }
    .confirm .input { flex: 1; min-width: 180px; }
    .danger { border-color: color-mix(in srgb, var(--critical) 40%, var(--border)); }
    .btn.delete { background: var(--critical); border-color: var(--critical); color: #fff; }
    .btn.delete:disabled { opacity: .45; cursor: not-allowed; }
  `,
})
export class AccountPage {
  private readonly session = inject(SessionService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly me = httpResource<Me>(() => `${this.session.apiUrl()}/api/me`);
  private readonly privacy = httpResource<{ conservationRevuesJours: number }>(
    () => `${this.session.apiUrl()}/api/me/privacy`);
  /** Durée réellement appliquée par l'API (SENTINEL_RETENTION_DAYS) ; valeur des documents légaux en attendant. */
  protected readonly retention = computed(() => this.privacy.value()?.conservationRevuesJours ?? LEGAL.conservationRevuesJours);
  protected readonly typed = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly login = computed(() => this.me.value()?.login ?? '');

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value.trim();
  }

  protected download(): void {
    this.busy.set(true);
    this.api.exportMyData().subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'pr-sentinel-mes-donnees.json';
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        this.busy.set(false);
      },
      error: () => {
        this.busy.set(false);
        this.error.set('Export impossible pour le moment. Réessayez.');
      },
    });
  }

  protected remove(): void {
    if (this.typed() !== this.login()) return;
    this.busy.set(true);
    this.api.deleteMyAccount().subscribe({
      next: () => {
        this.session.disconnect();
        void this.router.navigate(['/']);
      },
      error: () => {
        this.busy.set(false);
        this.error.set('Suppression impossible pour le moment. Réessayez ou écrivez-nous.');
      },
    });
  }
}
