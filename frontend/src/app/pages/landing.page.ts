import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionService } from '../session.service';
import { ThemeService } from '../ui/theme.service';
import { Icon } from '../ui/icon';
import { Logo } from '../ui/logo';

/** Page publique : présentation du service, résultats mesurés, appel à l'action. */
@Component({
  selector: 'app-landing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, Logo],
  template: `
    <header class="nav wrap">
      <a routerLink="/" class="plain"><app-logo /></a>
      <nav aria-label="Navigation du site">
        <a href="#fonctionnement" class="hide-sm">Fonctionnement</a>
        <a href="#resultats" class="hide-sm">Résultats</a>
        <a href="#offres" class="hide-sm">Offres</a>
        <button type="button" class="btn ghost iconic" (click)="theme.toggle()"
                [attr.aria-label]="theme.theme() === 'dark' ? 'Passer au thème clair' : 'Passer au thème sombre'">
          <app-icon [name]="theme.theme() === 'dark' ? 'sun' : 'moon'" />
        </button>
        <a class="btn primary" [routerLink]="session.connected() ? '/console' : '/connexion'">
          {{ session.connected() ? 'Ouvrir la console' : 'Se connecter' }}
        </a>
      </nav>
    </header>

    <section class="hero wrap">
      <div class="hero-copy">
        <span class="eyebrow"><app-icon name="spark" [size]="14" /> Revue de sécurité assistée par IA</span>
        <h1>Les vraies failles de vos pull requests.<br /><span class="accent">Sans le bruit.</span></h1>
        <p class="lead">
          PR Sentinel lance Semgrep, Gitleaks et Trivy sur chaque pull request, puis un agent lit le code
          pour écarter les fausses alertes. Il ne reste que les problèmes réels, commentés sur la bonne ligne,
          avec le correctif prêt à appliquer.
        </p>
        <div class="cta">
          <a class="btn primary lg" [routerLink]="session.connected() ? '/console' : '/connexion'">
            Essayer sur un dépôt <app-icon name="arrowRight" />
          </a>
          <a class="btn lg" href="#resultats">Voir les résultats mesurés</a>
        </div>
        <ul class="proof">
          <li><app-icon name="check" [size]="16" /> GitHub App, installation en 2 minutes</li>
          <li><app-icon name="check" [size]="16" /> Clone temporaire, effacé après l'analyse</li>
          <li><app-icon name="check" [size]="16" /> ≈ 0,008 $ par PR</li>
        </ul>
      </div>

      <!-- Aperçu d'un commentaire de revue, tel que publié sur GitHub. -->
      <figure class="preview card" aria-label="Exemple de commentaire publié sur une pull request">
        <div class="pv-head">
          <span class="file mono">src/main/java/demo/UserRepository.java</span>
          <span class="sev critique"><i aria-hidden="true"></i>Critique</span>
        </div>
        <pre class="pv-code"><span class="ln">16</span>  String sql = "SELECT * FROM users WHERE name = '"
<span class="ln del">17</span><span class="del">-     + name + "'";</span>
<span class="ln add">17</span><span class="add">+ String sql = "SELECT * FROM users WHERE name = ?";</span></pre>
        <div class="pv-body">
          <div class="who"><span class="avatar"><app-icon name="shield" [size]="14" /></span>
            <strong>pr-sentinel</strong><span class="muted">· vrai problème · confiance 100 %</span></div>
          <p>La requête SQL concatène une entrée utilisateur : injection possible. Utiliser une requête préparée.</p>
          <div class="pv-foot">
            <span class="status fail">Check en échec : fusion bloquée</span>
            <span class="muted small">2 fausses alertes écartées</span>
          </div>
        </div>
      </figure>
    </section>

    <section class="logos wrap" aria-label="Outils intégrés">
      <span class="muted small">Construit sur des scanners reconnus</span>
      <div class="tools"><span>Semgrep</span><span>Gitleaks</span><span>Trivy</span><span>GitHub Checks</span><span>OpenAI</span></div>
    </section>

    <section id="fonctionnement" class="wrap block">
      <div class="block-head">
        <span class="eyebrow">Fonctionnement</span>
        <h2>De la pull request au verdict, en une dizaine de secondes</h2>
      </div>
      <ol class="steps">
        @for (s of steps; track s.title; let i = $index) {
          <li class="card">
            <span class="step-n">{{ i + 1 }}</span>
            <span class="step-ic"><app-icon [name]="s.icon" [size]="20" /></span>
            <h3>{{ s.title }}</h3>
            <p class="text-2">{{ s.text }}</p>
          </li>
        }
      </ol>
    </section>

    <section id="resultats" class="wrap block">
      <div class="block-head">
        <span class="eyebrow">Résultats mesurés</span>
        <h2>Évalué sur des pull requests qu'il n'avait jamais vues</h2>
        <p class="text-2">Banc de 10 PR tenues à l'écart pendant toute la mise au point, passé trois fois :
          chiffres identiques à chaque passage. Comparé aux scanners seuls.</p>
      </div>
      <div class="results">
        @for (r of results; track r.label) {
          <div class="card result">
            <span class="r-value">{{ r.value }}</span>
            <span class="r-label">{{ r.label }}</span>
            <span class="r-hint muted">{{ r.hint }}</span>
          </div>
        }
      </div>
      <div class="card compare">
        <div class="cmp-row">
          <span class="cmp-name">Scanners seuls</span>
          <div class="cmp-bar" role="img" aria-label="Scanners seuls : 35,5 % de fausses alertes">
            <span [style.width.%]="35.5"></span></div>
          <span class="cmp-val">35,5 %</span>
        </div>
        <div class="cmp-row">
          <span class="cmp-name">Avec PR Sentinel</span>
          <div class="cmp-bar good" role="img" aria-label="Avec PR Sentinel : 20 % de fausses alertes">
            <span [style.width.%]="20"></span></div>
          <span class="cmp-val">20 %</span>
        </div>
        <p class="muted small">Part des alertes restantes qui sont des fausses alertes (plus bas = mieux). Jeu tenu à l'écart ;
          les chiffres du jeu de mise au point (12/12, 0 %) sont publiés à part dans la console.</p>
      </div>
    </section>

    <section class="wrap block">
      <div class="block-head">
        <span class="eyebrow">Pensé pour la sécurité</span>
        <h2>Un relecteur prudent, pas un oracle</h2>
      </div>
      <div class="features">
        @for (f of features; track f.title) {
          <div class="feature">
            <span class="f-ic"><app-icon [name]="f.icon" [size]="18" /></span>
            <div><h3>{{ f.title }}</h3><p class="text-2">{{ f.text }}</p></div>
          </div>
        }
      </div>
    </section>

    <section id="offres" class="wrap block">
      <div class="block-head">
        <span class="eyebrow">Offres</span>
        <h2>Simple à démarrer, prévisible à l'usage</h2>
      </div>
      <div class="plans">
        @for (p of plans; track p.name) {
          <div class="card plan" [class.featured]="p.featured">
            @if (p.featured) { <span class="badge-top">Recommandé</span> }
            <h3>{{ p.name }}</h3>
            <p class="price"><strong>{{ p.price }}</strong> <span class="muted">{{ p.unit }}</span></p>
            <p class="text-2 small">{{ p.pitch }}</p>
            <ul>
              @for (i of p.items; track i) { <li><app-icon name="check" [size]="16" /> {{ i }}</li> }
            </ul>
            <a class="btn" [class.primary]="p.featured" [routerLink]="session.connected() ? '/console' : '/connexion'">{{ p.cta }}</a>
          </div>
        }
      </div>
      <p class="muted small center">Tarifs indicatifs de lancement.</p>
    </section>

    <section class="wrap final card">
      <h2>Arrêtez de trier les alertes à la main.</h2>
      <p class="text-2">Installez la GitHub App, ouvrez une pull request : le premier verdict arrive avant la fin de votre café.</p>
      <a class="btn primary lg" [routerLink]="session.connected() ? '/console' : '/connexion'">
        Commencer <app-icon name="arrowRight" /></a>
    </section>

    <footer class="wrap foot">
      <app-logo />
      <span class="muted small">Java 21 · Spring Boot · Spring AI · Angular · PostgreSQL</span>
    </footer>
  `,
  styles: `
    :host { display: block; background:
      radial-gradient(1200px 500px at 85% -10%, color-mix(in srgb, var(--accent) 12%, transparent), transparent 70%), var(--bg); }
    .wrap { max-width: 1160px; margin: 0 auto; padding-left: clamp(1rem, 4vw, 2rem); padding-right: clamp(1rem, 4vw, 2rem); }
    .plain { text-decoration: none; }
    .nav { display: flex; align-items: center; justify-content: space-between; height: 72px; }
    .nav nav { display: flex; align-items: center; gap: 1.25rem; }
    .nav nav > a:not(.btn) { color: var(--text-2); font-size: .92rem; font-weight: 500; }
    .iconic { width: 38px; padding: 0; justify-content: center; }
    .eyebrow { display: inline-flex; align-items: center; gap: .4rem; font-size: .78rem; font-weight: 600; color: var(--accent);
      text-transform: uppercase; letter-spacing: .07em; }
    .hero { display: grid; grid-template-columns: 1.05fr 1fr; gap: clamp(2rem, 5vw, 4rem); align-items: center;
      padding-top: clamp(2rem, 6vw, 4.5rem); padding-bottom: 3rem; }
    .hero h1 { font-size: clamp(2.1rem, 4.6vw, 3.4rem); font-weight: 700; letter-spacing: -0.035em; line-height: 1.06; margin: 1rem 0 1.1rem; }
    .accent { color: var(--accent); }
    .lead { font-size: 1.08rem; color: var(--text-2); max-width: 36rem; line-height: 1.6; }
    .cta { display: flex; gap: .75rem; flex-wrap: wrap; margin: 1.8rem 0 1.4rem; }
    .proof { list-style: none; padding: 0; margin: 0; display: flex; flex-wrap: wrap; gap: .5rem 1.25rem; color: var(--text-2); font-size: .88rem; }
    .proof li { display: inline-flex; align-items: center; gap: .35rem; }
    .proof app-icon, .plan li app-icon { color: var(--good); }

    .preview { margin: 0; overflow: hidden; transform: rotate(.4deg); }
    .pv-head { display: flex; align-items: center; justify-content: space-between; gap: .75rem; padding: .7rem 1rem;
      border-bottom: 1px solid var(--border); background: var(--surface-sunken); }
    .file { font-size: .78rem; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pv-code { margin: 0; padding: .8rem 1rem; font: .8rem/1.7 var(--mono); overflow-x: auto; border-bottom: 1px solid var(--border); }
    .ln { display: inline-block; width: 2rem; color: var(--muted); }
    .del { background: color-mix(in srgb, var(--critical) 14%, transparent); }
    .add { background: color-mix(in srgb, var(--good) 16%, transparent); }
    .pv-body { padding: 1rem; display: grid; gap: .65rem; font-size: .9rem; }
    .who { display: flex; align-items: center; gap: .45rem; font-size: .85rem; flex-wrap: wrap; }
    .avatar { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; background: var(--accent); color: var(--on-accent); }
    .pv-foot { display: flex; align-items: center; justify-content: space-between; gap: .5rem; flex-wrap: wrap; padding-top: .25rem; }
    .small { font-size: .8rem; }
    .center { text-align: center; }

    .logos { display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap; padding-top: 1rem; padding-bottom: 1rem;
      border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
    .tools { display: flex; gap: clamp(1rem, 4vw, 2.5rem); flex-wrap: wrap; font-weight: 620; color: var(--muted); letter-spacing: -0.01em; }

    .block { padding-top: clamp(3rem, 8vw, 5.5rem); }
    .block-head { max-width: 44rem; margin-bottom: 2rem; display: grid; gap: .6rem; }
    .block-head h2 { font-size: clamp(1.5rem, 3vw, 2.1rem); font-weight: 680; letter-spacing: -0.03em; }
    .steps { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; }
    .steps li { padding: 1.3rem; display: grid; gap: .6rem; position: relative; align-content: start; }
    .step-n { position: absolute; top: 1rem; right: 1.1rem; font: 600 .8rem var(--mono); color: var(--muted); }
    .step-ic, .f-ic { width: 38px; height: 38px; border-radius: 9px; display: grid; place-items: center;
      background: var(--accent-soft); color: var(--accent); }
    .steps p, .feature p { font-size: .9rem; }

    .results { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; }
    .result { padding: 1.3rem; display: grid; gap: .3rem; }
    .r-value { font-size: 2.2rem; font-weight: 680; letter-spacing: -0.035em; font-variant-numeric: tabular-nums; }
    .r-label { font-weight: 560; }
    .r-hint { font-size: .82rem; }
    .compare { margin-top: 1rem; padding: 1.3rem; display: grid; gap: .9rem; }
    .cmp-row { display: grid; grid-template-columns: 150px 1fr 60px; align-items: center; gap: 1rem; font-size: .9rem; }
    .cmp-name { color: var(--text-2); }
    .cmp-bar { height: 12px; border-radius: 999px; background: var(--surface-sunken); overflow: hidden; }
    .cmp-bar span { display: block; height: 100%; border-radius: 999px; background: var(--neutral); }
    .cmp-bar.good span { background: var(--accent); }
    .cmp-val { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }

    .features { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.6rem 2rem; }
    .feature { display: flex; gap: .9rem; align-items: flex-start; }
    .feature h3 { margin-bottom: .25rem; }

    .plans { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 1rem; align-items: stretch; }
    .plan { padding: 1.5rem; display: flex; flex-direction: column; gap: .75rem; position: relative; }
    .plan.featured { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent), var(--shadow); }
    .badge-top { position: absolute; top: -11px; left: 1.5rem; background: var(--accent); color: var(--on-accent);
      font-size: .72rem; font-weight: 600; padding: .15rem .6rem; border-radius: 999px; }
    .price .muted { margin-left: .3rem; }
    .price strong { font-size: 1.9rem; letter-spacing: -0.03em; }
    .plan ul { list-style: none; padding: 0; margin: .25rem 0 .5rem; display: grid; gap: .5rem; font-size: .9rem; flex: 1; }
    .plan li { display: flex; gap: .45rem; align-items: flex-start; }
    .plan .btn { justify-content: center; }

    .final { margin-top: clamp(3rem, 8vw, 5.5rem); padding: clamp(1.75rem, 5vw, 3rem); text-align: center; display: grid; gap: .9rem;
      justify-items: center; background: linear-gradient(180deg, var(--accent-soft), var(--surface)); }
    .final h2 { font-size: clamp(1.4rem, 3vw, 2rem); letter-spacing: -0.03em; }
    .foot { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;
      padding-top: 2.5rem; padding-bottom: 2.5rem; }

    @media (max-width: 900px) {
      .hero { grid-template-columns: 1fr; }
      .preview { transform: none; }
    }
    @media (max-width: 640px) {
      .hide-sm { display: none; }
      .nav nav { gap: .5rem; }
      .cmp-row { grid-template-columns: 1fr 52px; }
      .cmp-name { grid-column: 1 / -1; }
    }
  `,
})
export class LandingPage {
  protected readonly session = inject(SessionService);
  protected readonly theme = inject(ThemeService);

  protected readonly steps = [
    { icon: 'git', title: 'Pull request ouverte', text: 'La GitHub App reçoit un webhook signé et place la revue dans une file PostgreSQL.' },
    { icon: 'scan', title: 'Trois scanners', text: 'Semgrep (code), Gitleaks (secrets) et Trivy (dépendances) analysent les fichiers modifiés.' },
    { icon: 'spark', title: 'Triage par un agent', text: "L'agent lit le code autour de chaque alerte, juge l'exploitabilité et fait confirmer les cas graves par un modèle plus fort." },
    { icon: 'comment', title: 'Revue publiée', text: 'Commentaires sur les lignes, correctif applicable en un clic, et check run qui bloque la fusion en cas de faille critique.' },
  ];

  protected readonly results = [
    { value: '6/6', label: 'failles détectées', hint: 'Aucune vraie faille manquée' },
    { value: '−67 %', label: 'de bruit', hint: 'Fausses alertes écartées par l’agent' },
    { value: '≈ 11 s', label: 'par pull request', hint: 'Scan et triage compris' },
    { value: '0,008 $', label: 'par pull request', hint: 'Coût moyen du modèle' },
  ];

  protected readonly features = [
    { icon: 'lock', title: 'Fermé par défaut', text: "Une alerte que l'agent n'a pas pu trier laisse le check en échec : jamais de feu vert par erreur." },
    { icon: 'layers', title: 'Second avis automatique', text: 'Les verdicts graves et les alertes graves écartées sont confirmés par un modèle plus fort.' },
    { icon: 'shield', title: 'Secrets masqués', text: "Les secrets détectés sont masqués avant tout envoi au modèle ; les fichiers sensibles ne sont jamais lus." },
    { icon: 'code', title: 'Correctifs applicables', text: 'Suggestions GitHub multi-lignes, ancrées sur les bonnes lignes et à la bonne indentation.' },
    { icon: 'alert', title: 'Garde-fous contre l’injection', text: 'Une instruction glissée dans le code ne suffit pas à faire taire une alerte grave : le modèle fort doit confirmer.' },
    { icon: 'coin', title: 'Budget plafonné', text: 'Plafond de coût et de durée par PR ; chaque revue affiche ses jetons et son coût exacts.' },
  ];

  protected readonly plans = [
    { name: 'Découverte', price: '0 €', unit: '/ mois', pitch: 'Pour essayer sur un projet personnel.', featured: false,
      cta: 'Commencer', items: ['1 dépôt', '50 revues par mois', 'Les 3 scanners + triage IA', 'Commentaires et check run'] },
    { name: 'Équipe', price: '19 €', unit: '/ dév. / mois', pitch: 'Pour les équipes qui livrent tous les jours.', featured: true,
      cta: 'Essai gratuit 14 jours', items: ['Dépôts illimités', 'Revues illimitées', 'Règles par dépôt', 'Alertes Slack et rapport hebdo', 'Historique et métriques'] },
    { name: 'Entreprise', price: 'Sur devis', unit: '', pitch: 'Pour les exigences de conformité.', featured: false,
      cta: 'Nous contacter', items: ['SSO et rôles', "Journal d'audit", 'Auto-hébergement', 'Modèle au choix', 'Support dédié'] },
  ];
}
