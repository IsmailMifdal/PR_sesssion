import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../api.service';
import { SessionService } from '../session.service';
import { Logo } from '../ui/logo';

/**
 * Retour de GitHub : le code de connexion arrive dans le fragment d'URL (jamais envoyé à un serveur ni
 * journalisé), est retiré de la barre d'adresse puis échangé une seule fois contre un jeton de session.
 */
@Component({
  selector: 'app-auth-callback',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Logo],
  template: `
    <main class="center">
      <div class="card box">
        <app-logo />
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
          <a class="btn primary" routerLink="/connexion">Réessayer</a>
        } @else {
          <div class="spinner" aria-hidden="true"></div>
          <p class="text-2">Connexion en cours…</p>
        }
      </div>
    </main>
  `,
  styles: `
    .center { min-height: 100dvh; display: grid; place-items: center; padding: 1rem; }
    .box { padding: 2rem; display: grid; gap: 1rem; justify-items: center; text-align: center; width: min(360px, 100%); }
    .spinner { width: 28px; height: 28px; border-radius: 50%; border: 3px solid var(--accent-soft); border-top-color: var(--accent);
      animation: spin .8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 3s; } }
  `,
})
export class AuthCallbackPage {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly error = signal('');

  constructor() {
    const code = new URLSearchParams(location.hash.slice(1)).get('code') ?? '';
    history.replaceState(null, '', location.pathname); // le code ne reste ni dans l'historique ni à l'écran
    const apiUrl = this.session.apiUrl();
    if (!code.startsWith('plc_') || !apiUrl) {
      this.error.set('Lien de connexion invalide ou expiré.');
      return;
    }
    this.api.redeem(apiUrl, code).subscribe({
      next: (r) => {
        this.session.connect(apiUrl, r.token);
        void this.router.navigate(['/console']);
      },
      error: () => this.error.set('Lien de connexion invalide ou expiré.'),
    });
  }
}
