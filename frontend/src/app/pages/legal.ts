/**
 * Identité de l'éditeur et paramètres des documents légaux. Les valeurs entre crochets sont à compléter
 * avant toute commercialisation (et les documents à faire relire par un juriste).
 */
export const LEGAL = {
  editeur: '[À compléter : raison sociale et forme juridique]',
  adresse: '[À compléter : adresse du siège]',
  immatriculation: '[À compléter : RCS / SIREN]',
  tva: '[À compléter : numéro de TVA intracommunautaire]',
  directeurPublication: '[À compléter : nom du directeur de la publication]',
  contact: '[À compléter : adresse e-mail de contact]',
  contactDonnees: '[À compléter : adresse e-mail pour les données personnelles]',
  conservationRevuesJours: 365,
  conservationJournaux:
    '[À compléter : durée de conservation des journaux techniques chez l’hébergeur]',
  droitApplicable: 'le droit français',
  tribunal: '[À compléter : tribunal compétent]',
  version: '24 novembre 2026',
};

/** Sous-traitants réellement utilisés par le service (voir le code et la configuration de déploiement). */
export const SOUS_TRAITANTS = [
  {
    nom: 'GitHub, Inc.',
    role: 'Authentification, lecture du code des pull requests, publication des revues',
    lieu: 'États-Unis',
  },
  {
    nom: 'OpenAI, L.L.C.',
    role: 'Modèle de langage qui trie les alertes (extraits de code autour des alertes, secrets masqués)',
    lieu: 'États-Unis',
  },
  {
    nom: 'Stripe',
    role: 'Paiement et facturation de l’offre Équipe',
    lieu: 'Union européenne / États-Unis',
  },
  {
    nom: '[À compléter : fournisseur d’envoi d’e-mails (SMTP)]',
    role: 'Envoi du rapport hebdomadaire',
    lieu: '[À compléter]',
  },
  {
    nom: 'Slack (Salesforce)',
    role: 'Réception des alertes, uniquement si le client configure un webhook vers son espace Slack',
    lieu: 'États-Unis',
  },
  {
    nom: 'Railway Corporation',
    role: 'Hébergement de l’API, du worker et de la base de données',
    lieu: '[À compléter : région de déploiement]',
  },
  {
    nom: 'Vercel Inc.',
    role: 'Hébergement du site et de la console (fichiers statiques)',
    lieu: '[À compléter : région de déploiement]',
  },
];
