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
  protected apiUrl = this.session.apiUrl() || location.origin;
  protected token = '';
  protected readonly error = signal('');

  submit(): void {
    if (!/^https?:\/\/[^\s]+$/.test(this.apiUrl.trim()) || this.token.trim().length < 32) {
      this.error.set('URL invalide ou jeton trop court (32 caractères minimum).');
      return;
    }
    this.session.connect(this.apiUrl, this.token);
    void this.router.navigate(['/revues']);
  }
}
