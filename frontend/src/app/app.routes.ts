import { CanActivateFn, Router, Routes } from '@angular/router';
import { inject } from '@angular/core';
import { SessionService } from './session.service';

const connected: CanActivateFn = () =>
  inject(SessionService).connected() || inject(Router).createUrlTree(['/connexion']);

export const routes: Routes = [
  { path: '', pathMatch: 'full', title: 'PR Sentinel — revue de sécurité des pull requests',
    loadComponent: () => import('./pages/landing.page').then((m) => m.LandingPage) },
  { path: 'confidentialite', title: 'Confidentialité · PR Sentinel', data: { doc: 'confidentialite' },
    loadComponent: () => import('./pages/legal.page').then((m) => m.LegalPage) },
  { path: 'conditions', title: "Conditions d'utilisation · PR Sentinel", data: { doc: 'conditions' },
    loadComponent: () => import('./pages/legal.page').then((m) => m.LegalPage) },
  { path: 'mentions-legales', title: 'Mentions légales · PR Sentinel', data: { doc: 'mentions' },
    loadComponent: () => import('./pages/legal.page').then((m) => m.LegalPage) },
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
      { path: 'demarrage', title: 'Démarrage · PR Sentinel',
        loadComponent: () => import('./pages/onboarding.page').then((m) => m.OnboardingPage) },
      { path: 'revues', title: 'Revues · PR Sentinel',
        loadComponent: () => import('./pages/reviews.page').then((m) => m.ReviewsPage) },
      { path: 'revues/:id', title: 'Revue · PR Sentinel',
        loadComponent: () => import('./pages/review-detail.page').then((m) => m.ReviewDetailPage) },
      { path: 'facturation', title: 'Facturation · PR Sentinel',
        loadComponent: () => import('./pages/billing.page').then((m) => m.BillingPage) },
      { path: 'notifications', title: 'Notifications · PR Sentinel',
        loadComponent: () => import('./pages/notifications.page').then((m) => m.NotificationsPage) },
      { path: 'compte', title: 'Mon compte · PR Sentinel',
        loadComponent: () => import('./pages/account.page').then((m) => m.AccountPage) },
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
