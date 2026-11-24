import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { authInterceptor } from './api.service';
import { SessionService } from './session.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let session: SessionService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionService);
    session.connect('https://api.example/', 'x'.repeat(40));
  });

  it("n'envoie le jeton qu'à l'API configurée", () => {
    http.get('https://api.example/api/reviews').subscribe();
    http.get('https://evil.example/api/reviews').subscribe();
    expect(ctrl.expectOne('https://api.example/api/reviews').request.headers.get('Authorization')).toBe(`Bearer ${'x'.repeat(40)}`);
    expect(ctrl.expectOne('https://evil.example/api/reviews').request.headers.has('Authorization')).toBe(false);
  });

  it('déconnecte et renvoie vers la connexion sur 401', () => {
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    http.get('https://api.example/api/metrics').subscribe({ error: () => undefined });
    ctrl.expectOne('https://api.example/api/metrics').flush('', { status: 401, statusText: 'Unauthorized' });
    expect(session.connected()).toBe(false);
    expect(nav).toHaveBeenCalledWith(['/connexion']);
  });

  it("normalise l'URL de l'API", () => {
    expect(session.apiUrl()).toBe('https://api.example');
  });

  it("n'envoie pas d'en-tête vide pendant l'échange du code de connexion", () => {
    session.disconnect();
    http.post('https://api.example/auth/session', { code: 'plc_x' }).subscribe();
    expect(ctrl.expectOne('https://api.example/auth/session').request.headers.has('Authorization')).toBe(false);
  });
});
