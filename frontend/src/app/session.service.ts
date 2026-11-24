import { Injectable, computed, signal } from '@angular/core';

/**
 * Connexion au tableau de bord : URL de l'API et jeton. Gardés en sessionStorage (effacés à la fermeture
 * de l'onglet), jamais dans le code ni dans l'URL.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly apiUrlSig = signal(read('sentinel.apiUrl') ?? '');
  private readonly tokenSig = signal(read('sentinel.token') ?? '');

  readonly apiUrl = this.apiUrlSig.asReadonly();
  readonly token = this.tokenSig.asReadonly();
  readonly connected = computed(() => this.apiUrlSig() !== '' && this.tokenSig() !== '');

  /** Mémorise l'API avant la redirection vers GitHub (sessionStorage survit à l'aller-retour). */
  setApiUrl(apiUrl: string): void {
    const url = apiUrl.trim().replace(/\/+$/, '');
    this.apiUrlSig.set(url);
    write('sentinel.apiUrl', url);
  }

  /** Page où revenir après la connexion GitHub (chemin interne de la console uniquement). */
  setNext(path: string): void {
    write('sentinel.next', path);
  }

  takeNext(): string {
    const next = read('sentinel.next') ?? '';
    remove('sentinel.next');
    return /^\/console(\/[\w/-]*)?$/.test(next) ? next : '/console';
  }

  connect(apiUrl: string, token: string): void {
    const url = apiUrl.trim().replace(/\/+$/, '');
    this.apiUrlSig.set(url);
    this.tokenSig.set(token.trim());
    write('sentinel.apiUrl', url);
    write('sentinel.token', token.trim());
  }

  disconnect(): void {
    this.tokenSig.set('');
    remove('sentinel.token');
  }
}

function read(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    /* stockage indisponible : la session reste en mémoire */
  }
}

function remove(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* rien à faire */
  }
}
