import { HttpClient, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, throwError } from 'rxjs';
import { Metrics, ReviewDetail, ReviewSummary } from './models';
import { SessionService } from './session.service';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);

  private url(path: string): string {
    return `${this.session.apiUrl()}/api${path}`;
  }

  reviews(filters: { gravite?: string; depot?: string }): Observable<ReviewSummary[]> {
    let params = new HttpParams().set('limit', 100);
    if (filters.gravite) params = params.set('gravite', filters.gravite);
    if (filters.depot) params = params.set('depot', filters.depot);
    return this.http.get<ReviewSummary[]>(this.url('/reviews'), { params });
  }

  review(id: number): Observable<ReviewDetail> {
    return this.http.get<ReviewDetail>(this.url(`/reviews/${id}`));
  }

  metrics(): Observable<Metrics> {
    return this.http.get<Metrics>(this.url('/metrics'));
  }
}

/** Ajoute le jeton aux seuls appels vers l'API configurée ; renvoie vers la connexion sur 401. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(SessionService);
  const router = inject(Router);
  const api = session.apiUrl();
  const authed = api !== '' && req.url.startsWith(api + '/')
    ? req.clone({ setHeaders: { Authorization: `Bearer ${session.token()}` } })
    : req;
  return next(authed).pipe(
    catchError((err) => {
      if (err?.status === 401) {
        session.disconnect();
        void router.navigate(['/connexion']);
      }
      return throwError(() => err);
    }),
  );
};
