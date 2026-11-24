import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SessionService } from '../session.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <section class="card narrow">
      <h1>Connexion au tableau de bord</h1>
      <p class="muted">Le jeton est celui de <code>SENTINEL_DASHBOARD_TOKEN</code>. Il reste dans cet onglet.</p>
      <form (ngSubmit)="submit()">
        <label for="api">URL de l'API</label>
        <input id="api" name="api" type="url" required placeholder="https://pr-sentinel.up.railway.app"
               [(ngModel)]="apiUrl" />
        <label for="token">Jeton</label>
        <input id="token" name="token" type="password" required autocomplete="current-password" [(ngModel)]="token" />
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
        <button type="submit">Se connecter</button>
      </form>
    </section>
  `,
})
export class LoginPage {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly apiUrl = signal(this.session.apiUrl() || '');

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
  protected token = '';
  protected readonly error = signal('');

  submit(): void {
    const url = this.apiUrl().trim();
    const secure = /^https:\/\/[^\s]+$/.test(url) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(url);
    if (!secure || this.token.trim().length < 32) {
      this.error.set("L'API doit être en HTTPS (sauf localhost) et le jeton faire au moins 32 caractères.");
      return;
    }
    this.session.connect(url, this.token);
    void this.router.navigate(['/revues']);
  }
}
