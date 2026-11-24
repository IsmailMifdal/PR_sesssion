export type Gravite = 'CRITIQUE' | 'HAUTE' | 'MOYENNE' | 'BASSE';
export type Decision = 'VRAI_PROBLEME' | 'FAUX_POSITIF' | 'A_VERIFIER';

export interface ReviewSummary {
  id: number;
  depot: string;
  pr: number;
  commit: string;
  statut: string;
  debut: string;
  dureeS: number | null;
  coutUsd: number;
  tokens: number;
  bloquante: boolean;
  alertes: number;
  vraisProblemes: number;
  aVerifier: number;
  fauxPositifs: number;
  graviteMax: Gravite | null;
}

export interface VerdictView {
  decision: Decision;
  gravite: Gravite;
  explication: string;
  correctif: string | null;
  confiance: number;
  modele: string;
}

export interface FindingView {
  id: number;
  outil: string;
  regle: string;
  fichier: string;
  ligne: number | null;
  graviteOutil: string;
  message: string;
  verdict: VerdictView | null;
  /** Retour de la personne connectée : true (juste), false (faux), null (aucun). */
  monAvis?: boolean | null;
}

export interface ReviewDetail {
  revue: ReviewSummary;
  alertes: FindingView[];
}

export interface EvalReport {
  date: string;
  mode: string;
  modele: string | null;
  metriques: Record<string, string | number | null>;
  objectifs: Record<string, string>;
  cas: { id: string; titre: string; attendu: Decision; detecte: boolean; alertesLignesModifiees: number;
    vraisProblemes: number; aVerifier: number; fauxPositifs: number; coutUsd: number; secondes: number }[];
}

export interface Metrics {
  revues: number;
  cout_moyen_usd: number;
  duree_moyenne_s: number | null;
  revues_bloquantes: number;
  cout_total_usd: number;
  alertes: number;
  vrais_problemes: number;
  a_verifier: number;
  faux_positifs: number;
  par_gravite: Partial<Record<Gravite, number>>;
  avis_justes?: number;
  avis_errones?: number;
  evaluation: EvalReport | null;
}

export interface Me {
  login: string;
  nom: string | null;
  avatarUrl: string | null;
  admin: boolean;
  depots: string[];
}

export interface BillingAccount {
  installationId: number;
  compte: string;
  offre: 'DECOUVERTE' | 'EQUIPE';
  statut: string | null;
  sieges: number | null;
  finPeriode: string | null;
  annulationFinPeriode: boolean;
  revuesCeMois: number;
  quota: number | null;
  gerable: boolean;
  essaiDisponible: boolean;
}

export interface Billing {
  actif: boolean;
  quotaDecouverte: number;
  joursEssai: number;
  comptes: BillingAccount[];
}

export interface Onboarding {
  installUrl: string | null;
  installations: number;
  depots: number;
  revues: number;
  premiereRevueId: number | null;
}

export interface ContestedVerdict {
  findingId: number;
  revueId: number;
  depot: string;
  pr: number;
  outil: string;
  regle: string;
  fichier: string;
  ligne: number | null;
  decision: Decision | null;
  gravite: Gravite | null;
  modele: string | null;
  utile: boolean;
  commentaire: string | null;
  auteur: string;
  date: string;
}

export interface NotificationAccount {
  installationId: number;
  compte: string;
  slackConfigure: boolean;
  slackIndice: string | null;
  slackNiveau: 'CRITIQUE' | 'VRAI_PROBLEME';
  emails: string[];
  rapportHebdo: boolean;
}

export interface Notifications {
  slackActif: boolean;
  emailActif: boolean;
  comptes: NotificationAccount[];
}
