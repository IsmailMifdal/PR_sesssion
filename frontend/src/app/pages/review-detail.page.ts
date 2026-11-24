import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FindingView, ReviewDetail } from '../models';
import { SessionService } from '../session.service';
import { GraviteBadge } from '../ui/gravite';
import { Icon } from '../ui/icon';
import { DEMO_REVIEW } from './demo';
import { ApiService } from '../api.service';

const DECISION: Record<string, { label: string; cls: string }> = {
  VRAI_PROBLEME: { label: 'Vrai problème', cls: 'd-issue' },
  A_VERIFIER: { label: 'À vérifier', cls: 'd-check' },
  FAUX_POSITIF: { label: 'Fausse alerte', cls: 'd-fp' },
};

@Component({
  selector: 'app-review-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DecimalPipe, PercentPipe, DatePipe, GraviteBadge, Icon],
  template: `
    <div class="page">
      <a routerLink="/console/revues" class="back"><app-icon name="arrowLeft" [size]="16" /> Revues</a>

      @if (demo()) {
        <div class="demo-note" role="note"><app-icon name="spark" [size]="16" /> Exemple de revue, avec des données fictives :
          voici ce que PR Sentinel publie sur une pull request.</div>
      }
      @if (failed()) {
        <div class="card empty error" role="alert">Revue introuvable.</div>
      } @else if (data(); as d) {
        <div class="page-head">
          <div>
            <h1>{{ d.revue.depot }} <span class="muted">#{{ d.revue.pr }}</span></h1>
            <p class="meta"><span class="mono">{{ d.revue.commit.slice(0, 12) }}</span> · {{ d.revue.debut | date: 'd MMMM y, HH:mm' }}
              · {{ statut(d.revue.statut) }}</p>
          </div>
          <div class="head-actions">
            @if (d.revue.bloquante) { <span class="status fail">Check en échec</span> } @else { <span class="status ok">Check réussi</span> }
            @if (!demo()) {
              <a class="btn" [href]="prUrl(d.revue.depot, d.revue.pr)" target="_blank" rel="noopener noreferrer">
                Voir sur GitHub <app-icon name="external" [size]="15" /></a>
            }
          </div>
        </div>

        @if (d.revue.bloquante) {
          <div class="banner" role="status"><app-icon name="alert" /> Faille critique détectée : le check run bloque la fusion
            tant qu'elle n'est pas corrigée.</div>
        }

        <div class="card strip">
          <div><span class="label">Alertes</span><span class="v">{{ d.revue.alertes }}</span></div>
          <div><span class="label">Vrais problèmes</span><span class="v">{{ d.revue.vraisProblemes }}</span></div>
          <div><span class="label">À vérifier</span><span class="v">{{ d.revue.aVerifier }}</span></div>
          <div><span class="label">Écartées</span><span class="v">{{ d.revue.fauxPositifs }}</span></div>
          <div><span class="label">Durée</span><span class="v">{{ d.revue.dureeS === null ? '—' : (d.revue.dureeS | number: '1.0-0') + ' s' }}</span></div>
          <div><span class="label">Coût</span><span class="v">{{ d.revue.coutUsd | number: '1.4-4' }} $</span>
            <span class="muted small">{{ d.revue.tokens | number }} jetons</span></div>
        </div>

        <div class="chips" role="group" aria-label="Filtrer les alertes">
          @for (f of filters; track f.value) {
            <button type="button" class="chip" [attr.aria-pressed]="filter() === f.value" (click)="filter.set(f.value)">
              {{ f.label }} <span class="count">{{ count(d.alertes, f.value) }}</span></button>
          }
        </div>

        <div class="findings">
          @for (a of visible(); track a.id) {
            <article class="card finding" [class]="'card finding ' + sevClass(a)">
              <header>
                <div class="f-title">
                  <app-gravite [value]="a.verdict?.gravite ?? null" />
                  <span [class]="'decision ' + decision(a).cls">{{ decision(a).label }}</span>
                </div>
                <span class="mono loc">{{ a.fichier }}{{ a.ligne ? ':' + a.ligne : '' }}</span>
              </header>
              <p class="rule muted"><span class="mono">{{ a.outil }}</span> · <span class="mono">{{ a.regle }}</span> · sévérité outil {{ a.graviteOutil }}</p>
              @if (a.verdict; as v) {
                <p class="expl">{{ v.explication }}</p>
                @if (v.correctif) {
                  <div class="fix">
                    <span class="fix-label"><app-icon name="code" [size]="15" /> Correctif proposé</span>
                    <pre class="code"><code>{{ v.correctif }}</code></pre>
                  </div>
                }
                <footer>
                  <div class="conf">
                    <span class="muted small">Confiance</span>
                    <div class="meter" role="img" [attr.aria-label]="'Confiance ' + (v.confiance | percent: '1.0-0')">
                      <span [style.width.%]="v.confiance * 100"></span></div>
                    <strong class="small">{{ v.confiance | percent: '1.0-0' }}</strong>
                  </div>
                  <span class="muted small mono">{{ v.modele }}</span>
                </footer>
                <div class="rate">
                  <span class="small text-2">Ce verdict est-il juste ?</span>
                  <button type="button" class="thumb" [attr.aria-pressed]="avis(a) === true" (click)="rate(a, true)"
                          aria-label="Verdict juste">👍</button>
                  <button type="button" class="thumb" [attr.aria-pressed]="avis(a) === false" (click)="rate(a, false)"
                          aria-label="Verdict faux">👎</button>
                  @if (saved()[a.id]) { <span class="small muted" role="status">{{ saved()[a.id] }}</span> }
                </div>
                @if (avis(a) === false && commenting() === a.id) {
                  <form class="why" (submit)="sendComment(a, $event)">
                    <label class="small text-2" [for]="'why-' + a.id">Qu'est-ce qui est faux ? (facultatif, aide à améliorer l'agent)</label>
                    <textarea class="input" [id]="'why-' + a.id" rows="2" maxlength="500"
                              placeholder="Ex. : l'entrée est déjà validée par le contrôleur."
                              [value]="draft()" (input)="draft.set(value($event))"></textarea>
                    <div class="why-actions">
                      <button type="submit" class="btn">Envoyer</button>
                      <button type="button" class="btn ghost" (click)="commenting.set(null)">Plus tard</button>
                    </div>
                  </form>
                }
              } @else {
                <p class="expl">{{ a.message }}</p>
                <p class="muted small">Alerte non triée (agent inactif, plafond ou budget atteint) : elle compte comme bloquante.</p>
              }
            </article>
          } @empty {
            <div class="card empty">Aucune alerte dans cette catégorie.</div>
          }
        </div>
      } @else {
        <div class="skeleton" style="height: 60px"></div>
        <div class="skeleton" style="height: 240px"></div>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: .35rem; color: var(--text-2); font-size: .88rem; width: fit-content; }
    .demo-note { display: flex; align-items: center; gap: .5rem; padding: .7rem 1rem; border-radius: var(--radius);
      background: var(--accent-soft); font-size: .88rem; }
    .demo-note app-icon { color: var(--accent); }
    .meta { color: var(--muted); font-size: .86rem; }
    .head-actions { display: flex; align-items: center; gap: .75rem; flex-wrap: wrap; }
    .banner { display: flex; align-items: center; gap: .6rem; padding: .8rem 1rem; border-radius: var(--radius);
      background: var(--critical-soft); border: 1px solid color-mix(in srgb, var(--critical) 35%, transparent); font-size: .9rem; }
    .banner app-icon { color: var(--critical); }
    .strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); }
    .strip > div { padding: .9rem 1.1rem; display: grid; gap: .15rem; border-right: 1px solid var(--border); }
    .strip > div:last-child { border-right: 0; }
    .label { font-size: .78rem; color: var(--text-2); }
    .v { font-size: 1.25rem; font-weight: 620; font-variant-numeric: tabular-nums; }
    .small { font-size: .78rem; }
    .count { font-variant-numeric: tabular-nums; color: var(--muted); font-size: .75rem; }
    .findings { display: grid; gap: .85rem; }
    .finding { padding: 1.1rem 1.2rem; display: grid; gap: .6rem; border-left: 3px solid var(--border-strong); }
    .finding.s-critique { border-left-color: var(--critical); }
    .finding.s-haute { border-left-color: var(--serious); }
    .finding.s-moyenne { border-left-color: var(--warning); }
    .finding.s-fp { opacity: .85; }
    header { display: flex; justify-content: space-between; align-items: center; gap: .5rem 1rem; flex-wrap: wrap; }
    .f-title { display: flex; align-items: center; gap: .75rem; }
    .decision { font-size: .76rem; font-weight: 600; padding: .12rem .5rem; border-radius: 5px; border: 1px solid var(--border); }
    .d-issue { background: var(--critical-soft); border-color: transparent; }
    .d-check { background: color-mix(in srgb, var(--warning) 18%, transparent); border-color: transparent; }
    .d-fp { color: var(--text-2); }
    .loc { font-size: .8rem; color: var(--text-2); overflow-wrap: anywhere; }
    .rule { font-size: .78rem; overflow-wrap: anywhere; }
    .expl { line-height: 1.6; }
    .fix { display: grid; gap: .4rem; }
    .fix-label { display: inline-flex; align-items: center; gap: .35rem; font-size: .8rem; font-weight: 560; color: var(--text-2); }
    .rate { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; border-top: 1px solid var(--border);
      padding-top: .7rem; }
    .thumb { width: 34px; height: 30px; border-radius: 7px; border: 1px solid var(--border); background: var(--surface);
      cursor: pointer; font-size: .95rem; filter: grayscale(1); opacity: .75; }
    .thumb:hover { opacity: 1; filter: none; }
    .thumb[aria-pressed="true"] { filter: none; opacity: 1; background: var(--accent-soft); border-color: var(--accent); }
    .why { display: grid; gap: .45rem; }
    .why textarea { height: auto; padding: .55rem .75rem; resize: vertical; }
    .why-actions { display: flex; gap: .5rem; }
    footer { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap; padding-top: .2rem; }
    .conf { display: flex; align-items: center; gap: .6rem; }
    .conf .meter { width: 120px; }
    @media (max-width: 600px) { .strip > div { border-right: 0; border-bottom: 1px solid var(--border); } }
  `,
})
export class ReviewDetailPage {
  private readonly session = inject(SessionService);
  readonly id = input.required<string>();
  protected readonly filters = [
    { value: 'TOUT', label: 'Toutes' },
    { value: 'CRITIQUE', label: 'Critiques' },
    { value: 'VRAI_PROBLEME', label: 'Vrais problèmes' },
    { value: 'A_VERIFIER', label: 'À vérifier' },
    { value: 'FAUX_POSITIF', label: 'Écartées' },
  ];
  protected readonly filter = signal('TOUT');

  protected readonly detail = httpResource<ReviewDetail>(() => {
    const id = Number(this.id());
    return Number.isInteger(id) && id > 0 ? `${this.session.apiUrl()}/api/reviews/${id}` : undefined;
  });

  private readonly api = inject(ApiService);
  /** Avis modifiés dans cette page (priment sur ceux chargés avec la revue). */
  private readonly localAvis = signal<Record<number, boolean | null>>({});
  protected readonly saved = signal<Record<number, string>>({});
  protected readonly commenting = signal<number | null>(null);
  protected readonly draft = signal('');
  protected readonly demo = computed(() => this.id() === 'demo');
  protected readonly data = computed(() => (this.demo() ? DEMO_REVIEW : this.detail.value()));
  protected readonly failed = computed(() => !this.demo() && !!this.detail.error());
  protected readonly visible = computed(() => select(this.data()?.alertes ?? [], this.filter()));

  protected count(all: FindingView[], f: string): number {
    return select(all, f).length;
  }

  protected decision(a: FindingView) {
    return DECISION[a.verdict?.decision ?? ''] ?? { label: 'Non triée', cls: 'd-check' };
  }

  protected sevClass(a: FindingView): string {
    if (a.verdict?.decision === 'FAUX_POSITIF') return 's-fp';
    return 's-' + (a.verdict?.gravite ?? 'basse').toLowerCase();
  }

  protected statut(s: string): string {
    return ({ TERMINEE: 'Terminée', EN_COURS: 'En cours', ECHEC: 'En échec' } as Record<string, string>)[s] ?? s.toLowerCase();
  }

  protected avis(a: FindingView): boolean | null {
    const local = this.localAvis();
    return a.id in local ? local[a.id] : (a.monAvis ?? null);
  }

  /** Un second clic sur le même pouce retire l'avis. */
  protected rate(a: FindingView, utile: boolean): void {
    const next = this.avis(a) === utile ? null : utile;
    const previous = this.avis(a);
    this.localAvis.update((m) => ({ ...m, [a.id]: next }));
    this.commenting.set(next === false ? a.id : null);
    this.draft.set('');
    if (this.demo()) {
      this.note(a.id, next === null ? '' : 'Merci (exemple : rien n’est enregistré)');
      return;
    }
    const call = next === null ? this.api.withdrawFeedback(a.id) : this.api.feedback(a.id, next);
    call.subscribe({
      next: () => this.note(a.id, next === null ? 'Avis retiré' : 'Merci pour votre retour'),
      error: () => {
        this.localAvis.update((m) => ({ ...m, [a.id]: previous }));
        this.note(a.id, 'Échec de l’envoi, réessayez');
      },
    });
  }

  protected sendComment(a: FindingView, e: Event): void {
    e.preventDefault();
    const text = this.draft().trim();
    this.commenting.set(null);
    if (!text || this.demo()) return;
    this.api.feedback(a.id, false, text).subscribe({
      next: () => this.note(a.id, 'Commentaire envoyé, merci'),
      error: () => this.note(a.id, 'Échec de l’envoi, réessayez'),
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLTextAreaElement).value;
  }

  private note(id: number, msg: string): void {
    this.saved.update((m) => ({ ...m, [id]: msg }));
  }

  protected prUrl(depot: string, pr: number): string | null {
    return /^[\w.-]+\/[\w.-]+$/.test(depot) ? `https://github.com/${depot}/pull/${pr}` : null;
  }
}

function select(all: FindingView[], f: string): FindingView[] {
  if (f === 'TOUT') return all;
  if (f === 'CRITIQUE') return all.filter((a) => a.verdict?.gravite === 'CRITIQUE' && a.verdict.decision !== 'FAUX_POSITIF');
  return all.filter((a) => a.verdict?.decision === f);
}
