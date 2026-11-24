import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
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
        <span class="muted small">La session reste dans cet onglet et expire au bout de 8 heures.</span>
      </aside>

      <main class="form-side">
        <div class="card form">
          <div class="head">
            <span class="lock"><app-icon name="lock" [size]="20" /></span>
            <h1>Connexion à la console</h1>
            <p class="text-2">Vous verrez les revues des dépôts où PR Sentinel est installé et auxquels vous avez accès.</p>
          </div>
          @if (errorMessage()) {
            <p class="error small" role="alert">{{ errorMessage() }}</p>
          }
          <button type="button" class="btn github lg" (click)="github()">
            <svg viewBox="0 0 16 16" width="18" height="18" aria-hidden="true" fill="currentColor">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
            </svg>
            Continuer avec GitHub
          </button>

          <details class="advanced" [open]="advancedOpen()">
            <summary>Accès opérateur (jeton d'administration)</summary>
            <form class="adv-form" (submit)="submit($event)">
              <div class="field">
                <label for="api">URL de l'API</label>
                <input id="api" name="api" class="input" type="url" required placeholder="https://pr-sentinel.up.railway.app"
                       [value]="apiUrl()" (input)="apiUrl.set(value($event))" />
              </div>
              <div class="field">
                <label for="token">Jeton <code>SENTINEL_DASHBOARD_TOKEN</code></label>
                <input id="token" name="token" class="input" type="password" required autocomplete="current-password"
                       [value]="token()" (input)="token.set(value($event))" />
              </div>
              <button type="submit" class="btn">Se connecter avec le jeton</button>
            </form>
          </details>
          <a routerLink="/" class="small back">← Retour au site</a>
        </div>
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
    .github { background: #1f2328; border-color: #1f2328; color: #fff; }
    .github:hover { background: #32383f; border-color: #32383f; }
    .advanced { border-top: 1px solid var(--border); padding-top: .9rem; }
    .advanced summary { cursor: pointer; font-size: .85rem; color: var(--text-2); }
    .adv-form { display: grid; gap: .9rem; margin-top: .9rem; }
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
  /** Motif d'échec renvoyé par l'API après GitHub (liste fermée : rien d'arbitraire n'est affiché). */
  readonly erreur = input<string>();
  protected readonly errorMessage = computed(() => this.error() || ({
    refus: 'Connexion annulée sur GitHub.',
    etat: 'La connexion a expiré ou a été ouverte dans un autre onglet. Réessayez.',
    github: 'GitHub a refusé la connexion. Réessayez dans un instant.',
  } as Record<string, string>)[this.erreur() ?? ''] || '');
  protected readonly advancedOpen = signal(false);

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

  /** Redirige vers l'API, qui redirige vers GitHub. */
  github(): void {
    const url = this.apiUrl().trim();
    if (!secureUrl(url)) {
      this.error.set("URL de l'API absente ou non sécurisée : ouvrez l'accès opérateur pour la renseigner.");
      this.advancedOpen.set(true);
      return;
    }
    this.session.setApiUrl(url);
    location.assign(url.replace(/\/+$/, '') + '/auth/github/login');
  }

  submit(e: Event): void {
    e.preventDefault();
    const url = this.apiUrl().trim();
    if (!secureUrl(url) || this.token().trim().length < 32) {
      this.error.set("L'API doit être en HTTPS (sauf localhost) et le jeton faire au moins 32 caractères.");
      return;
    }
    this.session.connect(url, this.token());
    void this.router.navigate(['/console']);
  }
}

function secureUrl(url: string): boolean {
  return /^https:\/\/[^\s]+$/.test(url) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(url);
}
