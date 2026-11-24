import { ReviewDetail } from '../models';

/** Revue d'exemple, montrée avant la première vraie revue (aucun appel à l'API). */
export const DEMO_REVIEW: ReviewDetail = {
  revue: {
    id: 0, depot: 'exemple/boutique', pr: 42, commit: '3f9c2a7d1e8b4c6a0f5d9e2b7a1c4d8e6f0a3b5c', statut: 'TERMINEE',
    debut: '2026-11-24T09:12:00Z', dureeS: 11, coutUsd: 0.0074, tokens: 8920, bloquante: true,
    alertes: 4, vraisProblemes: 2, aVerifier: 0, fauxPositifs: 2, graviteMax: 'CRITIQUE',
  },
  alertes: [
    { id: 1, outil: 'semgrep', regle: 'java.spring.security.audit.spring-sqli.spring-sqli',
      fichier: 'src/main/java/boutique/UserRepository.java', ligne: 17, graviteOutil: 'ERROR', message: 'SQL injection',
      verdict: { decision: 'VRAI_PROBLEME', gravite: 'CRITIQUE', confiance: 0.97, modele: 'gpt-4o',
        explication: "Le paramètre « email » vient de la requête HTTP et est concaténé dans la requête SQL : un attaquant peut lire ou modifier toute la table users.",
        correctif: 'String sql = "SELECT name FROM users WHERE email = ?";\nreturn jdbc.queryForList(sql, String.class, email);' } },
    { id: 2, outil: 'gitleaks', regle: 'generic-api-key', fichier: 'src/main/resources/application.yml', ligne: 12,
      graviteOutil: 'SECRET', message: 'Generic API key',
      verdict: { decision: 'VRAI_PROBLEME', gravite: 'HAUTE', confiance: 0.9, modele: 'gpt-4o',
        explication: "Clé d'API de paiement en clair dans la configuration versionnée : à révoquer, puis à lire depuis une variable d'environnement.",
        correctif: 'payment:\n  api-key: ${PAYMENT_API_KEY}' } },
    { id: 3, outil: 'semgrep', regle: 'java.lang.security.audit.crypto.use-of-md5', fichier: 'src/main/java/boutique/Cache.java',
      ligne: 30, graviteOutil: 'WARNING', message: 'MD5',
      verdict: { decision: 'FAUX_POSITIF', gravite: 'BASSE', confiance: 0.92, modele: 'gpt-4o-mini', correctif: null,
        explication: 'MD5 sert ici de clé de cache pour des images publiques : aucun usage cryptographique, pas de risque.' } },
    { id: 4, outil: 'gitleaks', regle: 'generic-api-key', fichier: 'src/test/resources/fixtures.json', ligne: 4,
      graviteOutil: 'SECRET', message: 'Generic API key',
      verdict: { decision: 'FAUX_POSITIF', gravite: 'BASSE', confiance: 0.95, modele: 'gpt-4o', correctif: null,
        explication: 'Valeur factice des tests (« test_key_000… »), jamais acceptée par un service réel. Confirmé par le modèle fort.' } },
  ],
};
