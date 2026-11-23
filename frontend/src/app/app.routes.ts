import { CanActivateFn, Router, Routes } from '@angular/router';
import { inject } from '@angular/core';
import { SessionService } from './session.service';

const connected: CanActivateFn = () =>
  inject(SessionService).connected() || inject(Router).createUrlTree(['/connexion']);

export const routes: Routes = [
  { path: 'connexion', loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage) },
  { path: 'revues', canActivate: [connected], loadComponent: () => import('./pages/reviews.page').then((m) => m.ReviewsPage) },
  { path: 'revues/:id', canActivate: [connected], loadComponent: () => import('./pages/review-detail.page').then((m) => m.ReviewDetailPage) },
  { path: 'metriques', canActivate: [connected], loadComponent: () => import('./pages/metrics.page').then((m) => m.MetricsPage) },
  { path: '', pathMatch: 'full', redirectTo: 'revues' },
  { path: '**', redirectTo: 'revues' },
];
