import { CanActivateFn, Router, Routes } from '@angular/router';
import { inject } from '@angular/core';
import { SessionService } from './session.service';

const connected: CanActivateFn = () =>
  inject(SessionService).connected() || inject(Router).createUrlTree(['/connexion']);

export const routes: Routes = [
  { path: '', pathMatch: 'full', title: 'PR Sentinel — revue de sécurité des pull requests',
    loadComponent: () => import('./pages/landing.page').then((m) => m.LandingPage) },
  { path: 'connexion', title: 'Connexion · PR Sentinel',
    loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage) },
  { path: 'auth/callback', title: 'Connexion · PR Sentinel',
    loadComponent: () => import('./pages/auth-callback.page').then((m) => m.AuthCallbackPage) },
  {
    path: 'console',
    canActivate: [connected],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', title: "Vue d'ensemble · PR Sentinel",
        loadComponent: () => import('./pages/overview.page').then((m) => m.OverviewPage) },
      { path: 'revues', title: 'Revues · PR Sentinel',
        loadComponent: () => import('./pages/reviews.page').then((m) => m.ReviewsPage) },
      { path: 'revues/:id', title: 'Revue · PR Sentinel',
        loadComponent: () => import('./pages/review-detail.page').then((m) => m.ReviewDetailPage) },
      { path: 'facturation', title: 'Facturation · PR Sentinel',
        loadComponent: () => import('./pages/billing.page').then((m) => m.BillingPage) },
      { path: 'evaluation', title: 'Évaluation · PR Sentinel',
        loadComponent: () => import('./pages/evaluation.page').then((m) => m.EvaluationPage) },
    ],
  },
  // Anciennes adresses.
  { path: 'revues', redirectTo: 'console/revues' },
  { path: 'revues/:id', redirectTo: 'console/revues/:id' },
  { path: 'metriques', redirectTo: 'console/evaluation' },
  { path: '**', redirectTo: '' },
];
