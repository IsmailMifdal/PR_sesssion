import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import { SessionService } from '../session.service';
import { ThemeService } from '../ui/theme.service';
import { Icon } from '../ui/icon';
import { Logo } from '../ui/logo';

/** Coquille de la console : barre latérale (bureau) ou tiroir (mobile). */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, Logo],
  template: `
    <div class="shell" [class.open]="menuOpen()">
      <header class="mobilebar">
        <a routerLink="/console"><app-logo /></a>
        <button type="button" class="iconbtn" (click)="menuOpen.set(!menuOpen())"
                [attr.aria-expanded]="menuOpen()" aria-controls="sidebar"
                [attr.aria-label]="menuOpen() ? 'Fermer le menu' : 'Ouvrir le menu'">
          <app-icon [name]="menuOpen() ? 'close' : 'menu'" [size]="20" />
        </button>
      </header>

      <aside id="sidebar" class="sidebar">
        <a routerLink="/" class="brand"><app-logo /></a>
        <nav aria-label="Navigation de la console">
          <span class="section">Console</span>
          <a routerLink="/console" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
            <app-icon name="grid" /> Vue d'ensemble</a>
          <a routerLink="/console/revues" routerLinkActive="active"><app-icon name="list" /> Revues</a>
          <a routerLink="/console/evaluation" routerLinkActive="active"><app-icon name="target" /> Évaluation</a>
        </nav>
        <div class="foot">
          <div class="conn" title="API connectée">
            <span class="dot" aria-hidden="true"></span>
            <span class="host">{{ host() }}</span>
          </div>
          <button type="button" class="navbtn" (click)="theme.toggle()">
            <app-icon [name]="theme.theme() === 'dark' ? 'sun' : 'moon'" />
            {{ theme.theme() === 'dark' ? 'Thème clair' : 'Thème sombre' }}
          </button>
          <button type="button" class="navbtn" (click)="logout()"><app-icon name="logout" /> Déconnexion</button>
        </div>
      </aside>

      <main class="content">
        <router-outlet />
      </main>
    </div>
  `,
  styles: `
    .shell { display: grid; grid-template-columns: 248px 1fr; min-height: 100dvh;
      background: linear-gradient(to right, var(--surface) 247px, var(--border) 247px 248px, transparent 248px); }
    .mobilebar { display: none; }
    .sidebar { position: sticky; top: 0; height: 100dvh; display: flex; flex-direction: column; gap: 1.5rem;
      padding: 1.1rem .85rem; border-right: 1px solid var(--border); background: var(--surface); }
    .brand { padding: .2rem .5rem; text-decoration: none; }
    nav { display: grid; gap: 2px; }
    .section { font-size: .7rem; font-weight: 600; text-transform: uppercase; letter-spacing: .08em; color: var(--muted);
      padding: 0 .65rem .45rem; }
    nav a, .navbtn { display: flex; align-items: center; gap: .65rem; height: 36px; padding: 0 .65rem; border-radius: 7px;
      color: var(--text-2); font: 500 .9rem var(--font); text-decoration: none; background: none; border: 0; cursor: pointer;
      width: 100%; text-align: left; }
    nav a:hover, .navbtn:hover { background: var(--surface-sunken); color: var(--text); text-decoration: none; }
    nav a.active { background: var(--accent-soft); color: var(--text); }
    nav a.active app-icon { color: var(--accent); }
    .foot { margin-top: auto; display: grid; gap: 2px; }
    .conn { display: flex; align-items: center; gap: .5rem; padding: .55rem .65rem .7rem; margin-bottom: .35rem;
      border-bottom: 1px solid var(--border); font-size: .78rem; color: var(--muted); min-width: 0; }
    .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--good); flex: none;
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--good) 20%, transparent); }
    .host { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--mono); }
    .content { min-width: 0; padding: 2rem clamp(1rem, 3vw, 2.5rem) 3rem; max-width: 1240px; width: 100%; }
    .iconbtn { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 8px; border: 1px solid var(--border);
      background: var(--surface); color: var(--text); cursor: pointer; }

    @media (max-width: 860px) {
      .shell { grid-template-columns: 1fr; grid-template-rows: auto 1fr; background: none; }
      .mobilebar { display: flex; align-items: center; justify-content: space-between; position: sticky; top: 0; z-index: 20;
        padding: .6rem 1rem; background: var(--surface); border-bottom: 1px solid var(--border); }
      .mobilebar a { text-decoration: none; }
      .sidebar { display: none; position: fixed; inset: 57px 0 0 0; z-index: 19; height: auto; border-right: 0; }
      .sidebar .brand { display: none; }
      .shell.open .sidebar { display: flex; }
      .content { padding: 1.25rem 1rem 2.5rem; }
    }
  `,
})
export class Shell {
  protected readonly session = inject(SessionService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  protected readonly menuOpen = signal(false);

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => this.menuOpen.set(false));
  }

  protected host(): string {
    try {
      return new URL(this.session.apiUrl()).host;
    } catch {
      return this.session.apiUrl();
    }
  }

  logout(): void {
    this.session.disconnect();
    void this.router.navigate(['/connexion']);
  }
}
