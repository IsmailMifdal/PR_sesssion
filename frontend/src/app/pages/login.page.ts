import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';
import { Logo } from '../ui/logo';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, Logo],
  template: `
    <div class="split">
      <aside class="side">
        <a routerLink="/" class="plain"><app-logo /></a>
        <div class="pitch">
          <h2>Moins d'alertes.<br />Plus de vraies failles.</h2>
          <ul>
            <li><app-icon name="check" [size]="16" /> Semgrep, Gitleaks et Trivy sur chaque PR</li>
            <li><app-icon name="check" [size]="16" /> Fausses alertes écartées par un agent</li>
            <li><app-icon name="check" [size]="16" /> Correctifs applicables en un clic</li>
          </ul>
        </div>
        <span class="muted small">Le jeton reste dans cet onglet et disparaît à sa fermeture.</span>
      </aside>

      <main class="form-side">
        <form class="card form" (submit)="submit($event)">
          <div class="head">
            <span class="lock"><app-icon name="lock" [size]="20" /></span>
            <h1>Connexion à la console</h1>
            <p class="text-2">Jeton d'accès défini par <code>SENTINEL_DASHBOARD_TOKEN</code>.</p>
          </div>
          <div class="field">
            <label for="api">URL de l'API</label>
            <input id="api" name="api" class="input" type="url" required placeholder="https://pr-sentinel.up.railway.app"
                   [value]="apiUrl()" (input)="apiUrl.set(value($event))" />
          </div>
          <div class="field">
            <label for="token">Jeton d'accès</label>
            <input id="token" name="token" class="input" type="password" required autocomplete="current-password"
                   [value]="token()" (input)="token.set(value($event))" />
          </div>
          @if (error()) {
            <p class="error small" role="alert">{{ error() }}</p>
          }
          <button type="submit" class="btn primary lg">Se connecter <app-icon name="arrowRight" [size]="16" /></button>
          <a routerLink="/" class="small back">← Retour au site</a>
        </form>
      </main>
    </div>
  `,
  styles: `
    .split { display: grid; grid-template-columns: minmax(320px, 0.9fr) 1.1fr; min-height: 100dvh; }
    .side { display: flex; flex-direction: column; justify-content: space-between; gap: 2rem; padding: 2rem clamp(1.5rem, 4vw, 3rem);
      background: radial-gradient(600px 400px at 0% 100%, color-mix(in srgb, var(--accent) 22%, transparent), transparent 70%), var(--surface);
      border-right: 1px solid var(--border); }
    .plain { text-decoration: none; }
    .pitch h2 { font-size: clamp(1.6rem, 3vw, 2.3rem); font-weight: 700; letter-spacing: -0.035em; margin-bottom: 1.4rem; }
    .pitch ul { list-style: none; padding: 0; margin: 0; display: grid; gap: .7rem; color: var(--text-2); }
    .pitch li { display: flex; align-items: center; gap: .5rem; }
    .pitch app-icon { color: var(--good); }
    .small { font-size: .8rem; }
    .form-side { display: grid; place-items: center; padding: 2rem 1rem; }
    .form { width: min(420px, 100%); padding: 2rem; display: grid; gap: 1.1rem; }
    .head { display: grid; gap: .45rem; margin-bottom: .25rem; }
    .head h1 { font-size: 1.35rem; }
    .lock { width: 42px; height: 42px; border-radius: 10px; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent);
      margin-bottom: .4rem; }
    .btn.lg { justify-content: center; }
    .back { color: var(--text-2); text-align: center; }
    @media (max-width: 820px) {
      .split { grid-template-columns: 1fr; }
      .side { border-right: 0; border-bottom: 1px solid var(--border); gap: 1rem; padding: 1.25rem 1rem; }
      .pitch, .side > .muted { display: none; }
    }
  `,
})
export class LoginPage {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly apiUrl = signal(this.session.apiUrl() || '');
  protected readonly token = signal('');
  protected readonly error = signal('');

  constructor() {
    // URL de l'API du déploiement (public/config.json), si l'utilisateur n'en a pas déjà saisi une.
    if (!this.apiUrl()) {
      fetch('config.json')
        .then((r) => (r.ok ? r.json() : null))
        .then((c: { apiUrl?: string } | null) => {
          if (!this.apiUrl() && typeof c?.apiUrl === 'string') this.apiUrl.set(c.apiUrl);
        })
        .catch(() => undefined);
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  submit(e: Event): void {
    e.preventDefault();
    const url = this.apiUrl().trim();
    const secure = /^https:\/\/[^\s]+$/.test(url) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(url);
    if (!secure || this.token().trim().length < 32) {
      this.error.set("L'API doit être en HTTPS (sauf localhost) et le jeton faire au moins 32 caractères.");
      return;
    }
    this.session.connect(url, this.token());
    void this.router.navigate(['/console']);
  }
}
