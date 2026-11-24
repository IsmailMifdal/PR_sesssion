import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { Logo } from '../ui/logo';
import { LEGAL, SOUS_TRAITANTS } from './legal';

/** Pages légales publiques : confidentialité (RGPD), conditions d'utilisation, mentions légales. */
@Component({
  selector: 'app-legal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Logo],
  template: `
    <header class="top wrap">
      <a routerLink="/" class="plain"><app-logo /></a>
      <nav aria-label="Documents légaux">
        <a routerLink="/confidentialite" [class.on]="doc() === 'confidentialite'">Confidentialité</a>
        <a routerLink="/conditions" [class.on]="doc() === 'conditions'">Conditions</a>
        <a routerLink="/mentions-legales" [class.on]="doc() === 'mentions'">Mentions légales</a>
      </nav>
    </header>

    <article class="doc wrap">
      @switch (doc()) {
        @case ('confidentialite') {
          <h1>Politique de confidentialité</h1>
          <p class="muted">Version du {{ L.version }}</p>

          <h2>1. Responsable du traitement</h2>
          <p>{{ L.editeur }}, {{ L.adresse }}. Contact pour vos données : {{ L.contactDonnees }}.</p>

          <h2>2. Données traitées, finalités et durées</h2>
          <table>
            <thead><tr><th>Données</th><th>Pourquoi</th><th>Combien de temps</th></tr></thead>
            <tbody>
              <tr><td>Compte GitHub : identifiant, login, nom public, avatar ; installations et dépôts auxquels vous avez
                accès, relevés à chaque connexion</td><td>Vous connecter et ne vous montrer que vos dépôts (exécution du
                contrat)</td><td>Jusqu'à la suppression de votre compte</td></tr>
              <tr><td>Code des pull requests</td><td>Analyse de sécurité. Le dépôt est cloné dans un dossier temporaire,
                effacé à la fin de chaque revue ; il n'est jamais conservé</td><td>Durée de l'analyse (quelques secondes)</td></tr>
              <tr><td>Extraits de code autour des alertes</td><td>Tri des alertes par un modèle de langage (OpenAI). Les
                secrets détectés sont masqués avant envoi ; les fichiers sensibles (<code>.env</code>, clés…) ne sont jamais
                lus</td><td>Non conservés par PR Sentinel</td></tr>
              <tr><td>Résultats : alertes (fichier, ligne, règle), verdicts, explications, correctifs proposés, coût</td>
                <td>Tableau de bord, historique et métriques</td>
                <td>{{ L.conservationRevuesJours }} jours, puis effacement automatique ; effacement immédiat si l'app est
                  désinstallée ou un dépôt retiré</td></tr>
              <tr><td>Avis 👍/👎 et commentaires sur les verdicts</td><td>Mesurer et améliorer la qualité (intérêt légitime)</td>
                <td>Comme la revue concernée, ou jusqu'à la suppression de votre compte</td></tr>
              <tr><td>Abonnement : identifiants client et abonnement Stripe, statut, nombre de sièges</td><td>Facturation
                (exécution du contrat, obligations comptables)</td><td>Durée de l'abonnement et obligations légales. Aucune
                donnée de carte ne transite par PR Sentinel</td></tr>
              <tr><td>Session : jeton stocké dans l'onglet du navigateur ; cookie technique d'état OAuth (10 minutes)</td>
                <td>Sécurité de la connexion</td><td>8 heures au plus</td></tr>
              <tr><td>Journaux techniques (dont login GitHub et dépôt concerné)</td><td>Sécurité et diagnostic</td>
                <td>{{ L.conservationJournaux }}</td></tr>
            </tbody>
          </table>
          <p>Aucun cookie publicitaire ni outil de mesure d'audience n'est utilisé. La préférence de thème clair ou sombre est
            gardée dans votre navigateur.</p>

          <h2>3. Sous-traitants</h2>
          <table>
            <thead><tr><th>Sous-traitant</th><th>Rôle</th><th>Localisation</th></tr></thead>
            <tbody>
              @for (s of soustraitants; track s.nom) { <tr><td>{{ s.nom }}</td><td>{{ s.role }}</td><td>{{ s.lieu }}</td></tr> }
            </tbody>
          </table>
          <p>Les transferts hors de l'Union européenne sont encadrés par les garanties prévues par le RGPD (clauses
            contractuelles types de la Commission européenne ou certification au cadre de protection des données
            UE–États-Unis). [À compléter : mécanisme retenu pour chaque sous-traitant.]</p>

          <h2>4. Vos droits</h2>
          <p>Vous disposez des droits d'accès, de rectification, d'effacement, de limitation, d'opposition et de portabilité.</p>
          <ul>
            <li><strong>Accès et portabilité :</strong> « Mon compte » → « Télécharger mes données » (fichier JSON).</li>
            <li><strong>Effacement :</strong> « Mon compte » → « Supprimer mon compte » ; désinstaller l'app sur GitHub efface
              aussitôt les revues du compte concerné.</li>
            <li><strong>Rectification :</strong> votre nom et votre avatar viennent de votre profil GitHub et sont mis à jour à
              chaque connexion.</li>
            <li>Pour toute autre demande : {{ L.contactDonnees }}.</li>
          </ul>
          <p>Vous pouvez introduire une réclamation auprès de la CNIL (cnil.fr).</p>
        }

        @case ('conditions') {
          <h1>Conditions générales d'utilisation</h1>
          <p class="muted">Version du {{ L.version }}</p>

          <h2>1. Objet</h2>
          <p>PR Sentinel, édité par {{ L.editeur }}, analyse la sécurité des pull requests des dépôts GitHub où son
            application est installée, et publie une revue (commentaires, correctifs proposés, check run).</p>

          <h2>2. Accès</h2>
          <p>L'accès se fait par un compte GitHub. Vous ne pouvez installer l'application que sur des comptes et dépôts pour
            lesquels vous disposez des autorisations nécessaires.</p>

          <h2>3. Offres</h2>
          <ul>
            <li><strong>Découverte</strong> : gratuite, limitée à un nombre de revues par mois et par compte GitHub, affiché
              dans la console. Au-delà, les pull requests ne sont plus analysées jusqu'au mois suivant ; un check « neutre »
              l'indique sur chaque pull request concernée.</li>
            <li><strong>Équipe</strong> : abonnement mensuel facturé par siège, avec une période d'essai offerte une fois par
              compte. Sans engagement : la résiliation, depuis le portail client, prend effet à la fin de la période en
              cours.</li>
          </ul>
          <p>Les prix sont indiqués hors taxes ; le montant exact figure sur la page de paiement. Le paiement est traité par
            Stripe.</p>

          <h2>4. Nature du service</h2>
          <p>PR Sentinel est une aide à la revue de code. Les verdicts sont produits automatiquement, par des outils d'analyse
            et un modèle de langage : ils peuvent être erronés. Le service ne garantit pas l'absence de vulnérabilités et ne
            remplace ni une revue humaine ni un audit de sécurité. Vous restez seul juge des modifications fusionnées.</p>

          <h2>5. Disponibilité</h2>
          <p>Le service est fourni en l'état, avec une obligation de moyens. Des interruptions peuvent survenir pour
            maintenance ou incident. [À compléter : engagement de disponibilité éventuel.]</p>

          <h2>6. Responsabilité</h2>
          <p>[À compléter et faire valider : limitation de responsabilité de l'éditeur.]</p>

          <h2>7. Données</h2>
          <p>Le traitement des données est décrit dans la <a routerLink="/confidentialite">politique de confidentialité</a>.</p>

          <h2>8. Droit applicable</h2>
          <p>Les présentes conditions sont soumises à {{ L.droitApplicable }}. Compétence : {{ L.tribunal }}.</p>
        }

        @default {
          <h1>Mentions légales</h1>
          <h2>Éditeur</h2>
          <p>{{ L.editeur }}<br />{{ L.adresse }}<br />{{ L.immatriculation }}<br />{{ L.tva }}<br />Contact : {{ L.contact }}</p>
          <h2>Directeur de la publication</h2>
          <p>{{ L.directeurPublication }}</p>
          <h2>Hébergement</h2>
          <p>Site et console : Vercel Inc. [À compléter : adresse].<br />
            API et base de données : Railway Corporation [À compléter : adresse].</p>
        }
      }
    </article>

    <footer class="wrap foot">
      <a routerLink="/">← Retour au site</a>
    </footer>
  `,
  styles: `
    :host { display: block; min-height: 100dvh; }
    .wrap { max-width: 860px; margin: 0 auto; padding-left: clamp(1rem, 4vw, 2rem); padding-right: clamp(1rem, 4vw, 2rem); }
    .plain { text-decoration: none; }
    .top { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap;
      padding-top: 1.2rem; padding-bottom: 1.2rem; }
    .top nav { display: flex; gap: 1rem; flex-wrap: wrap; font-size: .9rem; }
    .top nav a { color: var(--text-2); }
    .top nav a.on { color: var(--text); font-weight: 600; }
    .doc { padding-top: 1rem; padding-bottom: 2rem; line-height: 1.65; }
    .doc h1 { font-size: clamp(1.6rem, 3vw, 2.1rem); margin-bottom: .3rem; }
    .doc h2 { margin: 2rem 0 .6rem; }
    .doc p, .doc ul { margin: 0 0 .8rem; color: var(--text-2); }
    .doc li { margin-bottom: .35rem; }
    .doc table { margin: .4rem 0 1rem; font-size: .88rem; display: block; overflow-x: auto; }
    .doc th { white-space: normal; }
    .doc td { vertical-align: top; color: var(--text-2); padding: .65rem .8rem; }
    .doc th { padding: .55rem .8rem; }
    .foot { padding-bottom: 2.5rem; }
  `,
})
export class LegalPage {
  protected readonly L = LEGAL;
  protected readonly soustraitants = SOUS_TRAITANTS;
  protected readonly doc = toSignal(inject(ActivatedRoute).data.pipe(map((d) => d['doc'] as string)), {
    initialValue: 'confidentialite',
  });
}
