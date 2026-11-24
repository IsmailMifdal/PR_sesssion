import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

/** Thème choisi par l'utilisateur (sinon celui du système). Préférence locale, sans conséquence si perdue. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(initial());

  constructor() {
    this.apply(this.theme());
  }

  toggle(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    this.theme.set(next);
    this.apply(next);
    try {
      localStorage.setItem('sentinel.theme', next);
    } catch {
      /* stockage indisponible : le choix vaut pour cette page */
    }
  }

  private apply(t: Theme): void {
    document.documentElement.dataset['theme'] = t;
  }
}

function initial(): Theme {
  try {
    const saved = localStorage.getItem('sentinel.theme');
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    /* rien */
  }
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
