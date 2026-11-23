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
