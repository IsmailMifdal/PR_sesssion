import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { Me, Onboarding } from '../models';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';

/** Fichier de la PR de démonstration : injection SQL volontaire, détectée à coup sûr (cas 01 du banc). */
export const DEMO_COMMANDS = `git checkout -b pr-sentinel-demo
mkdir -p pr-sentinel-demo
cat > pr-sentinel-demo/UserRepository.java <<'JAVA'
package demo;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Faille volontaire pour la démonstration : ne pas fusionner.
    public List<String> findByEmail(String email) {
        String sql = "SELECT name FROM users WHERE email = '" + email + "'";
        return jdbc.queryForList(sql, String.class);
    }
}
JAVA
git add pr-sentinel-demo
git commit -m "Démo PR Sentinel : injection SQL volontaire"
git push -u origin pr-sentinel-demo`;

@Component({
  selector: 'app-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Démarrage</h1>
          <p>Quatre étapes, environ cinq minutes, jusqu'au premier verdict sur une vraie pull request.</p>
        </div>
        <a class="btn" routerLink="/console/revues/demo"><app-icon name="spark" [size]="16" /> Voir un exemple de revue</a>
      </div>

      @if (state.value(); as o) {
        <div class="card progress-card">
          <div class="p-top"><strong>{{ doneCount() }} / 4 étapes</strong>
            @if (doneCount() === 4) { <span class="status ok">Tout est prêt</span> }</div>
          <div class="meter"><span [style.width.%]="doneCount() * 25"></span></div>
        </div>

        <ol class="steps">
          <!-- 1. Installer -->
          <li class="card step" [class.done]="o.installations > 0" [class.current]="current() === 1">
            <span class="badge">@if (o.installations > 0) { <app-icon name="check" [size]="16" /> } @else { 1 }</span>
            <div class="body">
              <h2>Installer PR Sentinel sur GitHub</h2>
              <p class="text-2">Sur votre compte ou votre organisation. Permissions minimales : lecture du code,
                écriture des commentaires de revue et des checks.</p>
              @if (o.installations > 0) {
                <p class="ok-line">{{ o.installations }} installation(s) détectée(s).</p>
              } @else if (o.installUrl) {
                <div class="row">
                  <a class="btn primary" [href]="o.installUrl"><app-icon name="git" [size]="16" /> Installer la GitHub App</a>
                  @if (!admin()) {
                    <button type="button" class="btn ghost" (click)="resync()">Déjà installée ? Actualiser</button>
                  }
                </div>
              } @else {
                <p class="muted">Lien d'installation non configuré (SENTINEL_GITHUB_APP_SLUG) : demandez-le à l'administrateur.</p>
              }
            </div>
          </li>

          <!-- 2. Dépôts -->
          <li class="card step" [class.done]="o.depots > 0" [class.current]="current() === 2">
            <span class="badge">@if (o.depots > 0) { <app-icon name="check" [size]="16" /> } @else { 2 }</span>
            <div class="body">
              <h2>Choisir les dépôts à surveiller</h2>
              <p class="text-2">Pendant l'installation, choisissez « All repositories » ou une sélection. Seules les pull
                requests de ces dépôts sont analysées.</p>
              @if (o.depots > 0) {
                <p class="ok-line">{{ o.depots }} dépôt(s) surveillé(s).
                  @if (o.installUrl) { <a [href]="o.installUrl">Modifier la sélection</a> }</p>
              } @else if (o.installations > 0 && !admin()) {
                <button type="button" class="btn" (click)="resync()">Actualiser après avoir choisi les dépôts</button>
              }
            </div>
          </li>

          <!-- 3. PR de démonstration -->
          <li class="card step" [class.done]="o.revues > 0" [class.current]="current() === 3">
            <span class="badge">@if (o.revues > 0) { <app-icon name="check" [size]="16" /> } @else { 3 }</span>
            <div class="body">
              <h2>Ouvrir une pull request de démonstration</h2>
              <p class="text-2">Dans un clone d'un dépôt surveillé, ces commandes ajoutent sur une nouvelle branche un fichier
                contenant une injection SQL volontaire. Ouvrez ensuite la pull request sur GitHub, sans la fusionner.</p>
              @if (o.revues === 0) {
                <div class="snippet">
                  <pre class="code"><code>{{ commands }}</code></pre>
                  <button type="button" class="btn copy" (click)="copy()">
                    <app-icon [name]="copied() ? 'check' : 'code'" [size]="15" /> {{ copied() ? 'Copié' : 'Copier' }}</button>
                </div>
                @if (o.depots > 0) {
                  <p class="waiting"><span class="pulse" aria-hidden="true"></span> En attente de la première revue…
                    (actualisation automatique)</p>
                }
              } @else {
                <p class="ok-line">Première revue reçue.</p>
              }
            </div>
          </li>

          <!-- 4. Verdict -->
          <li class="card step" [class.done]="o.premiereRevueId !== null" [class.current]="current() === 4">
            <span class="badge">@if (o.premiereRevueId !== null) { <app-icon name="check" [size]="16" /> } @else { 4 }</span>
            <div class="body">
              <h2>Lire le premier verdict</h2>
              <p class="text-2">Sur GitHub : un commentaire sur la ligne fautive avec le correctif, et un check qui bloque la
                fusion. Ici : le détail, la confiance et le coût de la revue.</p>
              @if (o.premiereRevueId !== null) {
                <a class="btn primary" [routerLink]="['/console/revues', o.premiereRevueId]">Ouvrir la revue
                  <app-icon name="arrowRight" [size]="16" /></a>
              }
            </div>
          </li>
        </ol>
      } @else if (state.error()) {
        <div class="card empty error" role="alert">Chargement impossible.</div>
      } @else {
        <div class="skeleton" style="height: 320px"></div>
      }
    </div>
  `,
  styles: `
    .progress-card { padding: 1rem 1.2rem; display: grid; gap: .6rem; }
    .p-top { display: flex; justify-content: space-between; align-items: center; }
    .steps { list-style: none; padding: 0; margin: 0; display: grid; gap: .85rem; }
    .step { display: flex; gap: 1rem; padding: 1.2rem; align-items: flex-start; }
    .step.current { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent), var(--shadow); }
    .step.done h2 { color: var(--text-2); }
    .badge { flex: none; width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; font-weight: 650;
      font-size: .9rem; background: var(--surface-sunken); border: 1px solid var(--border); }
    .step.current .badge { background: var(--accent); color: var(--on-accent); border-color: var(--accent); }
    .step.done .badge { background: var(--good-soft); border-color: transparent; color: var(--good); }
    .body { display: grid; gap: .6rem; min-width: 0; flex: 1; }
    .row { display: flex; gap: .5rem; flex-wrap: wrap; }
    .ok-line { color: var(--text-2); font-size: .9rem; }
    .snippet { position: relative; min-width: 0; }
    .snippet pre { max-height: 260px; font-size: .78rem; }
    .copy { position: absolute; top: .5rem; right: .5rem; height: 30px; font-size: .8rem; padding: 0 .6rem; }
    .waiting { display: flex; align-items: center; gap: .55rem; font-size: .88rem; color: var(--text-2); }
    .pulse { width: 9px; height: 9px; border-radius: 50%; background: var(--accent); animation: pulse 1.4s ease-in-out infinite; }
    @keyframes pulse { 50% { opacity: .25; } }
    @media (prefers-reduced-motion: reduce) { .pulse { animation: none; } }
  `,
})
export class OnboardingPage {
  private readonly session = inject(SessionService);
  /** Paramètre ajouté par GitHub au retour de l'installation (« Setup URL » de l'app). */
  readonly setupAction = input<string>(undefined, { alias: 'setup_action' });
  protected readonly state = httpResource<Onboarding>(() => `${this.session.apiUrl()}/api/onboarding`);
  private readonly me = httpResource<Me>(() => `${this.session.apiUrl()}/api/me`);
  protected readonly admin = computed(() => this.me.value()?.admin ?? false);
  protected readonly commands = DEMO_COMMANDS;
  protected readonly copied = signal(false);

  protected readonly doneCount = computed(() => {
    const o = this.state.value();
    if (!o) return 0;
    return [o.installations > 0, o.depots > 0, o.revues > 0, o.premiereRevueId !== null].filter(Boolean).length;
  });
  protected readonly current = computed(() => {
    const o = this.state.value();
    if (!o) return 0;
    return o.installations === 0 ? 1 : o.depots === 0 ? 2 : o.revues === 0 ? 3 : o.premiereRevueId === null ? 4 : 0;
  });

  constructor() {
    // Retour de GitHub après installation : installations et dépôts se relèvent à la connexion, on se
    // reconnecte donc une fois (GitHub ne redemande rien si l'autorisation existe déjà).
    queueMicrotask(() => {
      const action = this.setupAction();
      if ((action === 'install' || action === 'update') && !once('sentinel.resynced')) {
        this.resync();
      }
    });
    // En attente de la première revue : relecture périodique.
    const timer = setInterval(() => {
      const o = this.state.value();
      if (o && o.depots > 0 && o.revues === 0) this.state.reload();
    }, 10000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected resync(): void {
    this.session.setNext('/console/demarrage');
    location.assign(this.session.apiUrl() + '/auth/github/login');
  }

  protected copy(): void {
    void navigator.clipboard?.writeText(DEMO_COMMANDS).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }
}

/** Vrai si la clé était déjà posée dans cet onglet ; la pose sinon (évite une boucle de reconnexion). */
function once(key: string): boolean {
  try {
    if (sessionStorage.getItem(key)) return true;
    sessionStorage.setItem(key, '1');
  } catch {
    return true;
  }
  return false;
}
