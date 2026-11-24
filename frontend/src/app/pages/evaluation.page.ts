import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ContestedVerdict, Metrics } from '../models';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';

type Value = string | number | null | undefined;
interface Goal { key: string; label: string; unit: string; goal: string; ok: (v: Value) => boolean | null }

const num = (v: Value): number | null => (typeof v === 'number' ? v : null);

@Component({
  selector: 'app-evaluation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, Icon, RouterLink],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Évaluation</h1>
          <p>Banc de pull requests aux failles connues, avec pièges (fausses alertes attendues). Mesuré à chaque release.</p></div>
      </div>

      @if (metrics.error()) {
        <div class="card empty error" role="alert">Chargement impossible.</div>
      } @else if (metrics.value(); as m) {
        @if (m.evaluation; as e) {
          <p class="muted small">Dernier passage : {{ e.date | date: 'd MMMM y, HH:mm' }} · mode {{ e.mode }}{{ e.modele ? ' · ' + e.modele : '' }}
            · {{ e.cas.length }} cas</p>

          <div class="goals">
            @for (g of goals; track g.key) {
              @let v = e.metriques[g.key];
              @let ok = g.ok(v);
              <div class="card goal">
                <div class="g-top">
                  <span class="label">{{ g.label }}</span>
                  @if (ok === true) { <span class="pill pass"><app-icon name="check" [size]="13" /> Atteint</span> }
                  @if (ok === false) { <span class="pill miss"><app-icon name="x" [size]="13" /> Manqué</span> }
                </div>
                <span class="value">{{ format(v, g.unit) }}</span>
                <span class="muted small">Objectif {{ g.goal }}</span>
              </div>
            }
          </div>

          <section class="card">
            <div class="card-head"><h2>Détail des cas</h2>
              <span class="muted small">{{ detected(e.cas) }} / {{ failles(e.cas) }} failles détectées</span></div>
            <div class="table-wrap">
              <table>
                <thead><tr><th>Cas</th><th>Attendu</th><th class="num">Alertes</th><th class="num">Vrais</th>
                  <th class="num">À vérifier</th><th class="num">Écartées</th><th>Résultat</th></tr></thead>
                <tbody>
                  @for (c of e.cas; track c.id) {
                    <tr>
                      <td><span class="mono muted">{{ c.id }}</span><div>{{ c.titre }}</div></td>
                      <td>{{ c.attendu === 'VRAI_PROBLEME' ? 'Faille' : 'Piège' }}</td>
                      <td class="num">{{ c.alertesLignesModifiees }}</td>
                      <td class="num">{{ c.vraisProblemes }}</td>
                      <td class="num">{{ c.aVerifier }}</td>
                      <td class="num">{{ c.fauxPositifs }}</td>
                      <td>
                        @if (c.attendu === 'VRAI_PROBLEME') {
                          @if (c.detecte) { <span class="status ok">Détectée</span> } @else { <span class="status fail">Manquée</span> }
                        } @else {
                          @if (c.vraisProblemes === 0) { <span class="status ok">Silencieux</span> }
                          @else { <span class="status fail">Fausse alerte</span> }
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        } @else {
          <div class="card empty">
            <p><strong>Aucun rapport d'évaluation publié.</strong></p>
            <p>Lancez le banc (<span class="mono">eval/</span>) : le rapport apparaît ici.</p>
          </div>
        }

        <section class="card">
          <div class="card-head">
            <h2>Retours des utilisateurs</h2>
            @if (precision(m); as p) {
              <span class="small"><strong>{{ p }}</strong> <span class="muted">des verdicts notés jugés justes
                ({{ (m.avis_justes ?? 0) + (m.avis_errones ?? 0) }} avis)</span></span>
            }
          </div>
          @if (contested.value(); as list) {
            @if (list.length === 0) {
              <p class="empty">Aucun verdict contesté. Les 👎 donnés sur les revues apparaissent ici : ce sont les prochains
                cas à ajouter au banc.</p>
            } @else {
              <ul class="contested">
                @for (c of list; track c.findingId) {
                  <li>
                    <div class="c-top">
                      <a [routerLink]="['/console/revues', c.revueId]"><strong>{{ c.depot }}</strong> #{{ c.pr }}</a>
                      <span class="mono small muted">{{ c.fichier }}{{ c.ligne ? ':' + c.ligne : '' }}</span>
                    </div>
                    <p class="small">Verdict contesté : <strong>{{ decisionLabel(c.decision) }}</strong>
                      @if (c.gravite) { ({{ c.gravite.toLowerCase() }}) } · <span class="mono">{{ c.outil }} · {{ c.regle }}</span></p>
                    @if (c.commentaire) { <blockquote>{{ c.commentaire }}</blockquote> }
                    <p class="small muted">{{ c.auteur }} · {{ c.date | date: 'd MMM, HH:mm' }}{{ c.modele ? ' · ' + c.modele : '' }}</p>
                  </li>
                }
              </ul>
            }
          }
        </section>
      } @else {
        <div class="skeleton" style="height: 300px"></div>
      }
    </div>
  `,
  styles: `
    .small { font-size: .8rem; }
    .goals { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1rem; }
    .goal { padding: 1.05rem 1.15rem; display: grid; gap: .3rem; }
    .g-top { display: flex; justify-content: space-between; align-items: flex-start; gap: .5rem; }
    .label { font-size: .84rem; color: var(--text-2); }
    .value { font-size: 1.7rem; font-weight: 640; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
    .pill { display: inline-flex; align-items: center; gap: .25rem; font-size: .72rem; font-weight: 600; padding: .12rem .45rem;
      border-radius: 999px; white-space: nowrap; }
    .pill.pass { background: var(--good-soft); }
    .pill.pass app-icon { color: var(--good); }
    .pill.miss { background: var(--critical-soft); }
    .pill.miss app-icon { color: var(--critical); }
    .contested { list-style: none; margin: 0; padding: 0; }
    .contested li { padding: .9rem 1.2rem; display: grid; gap: .3rem; }
    .contested li + li { border-top: 1px solid var(--border); }
    .c-top { display: flex; justify-content: space-between; gap: .5rem 1rem; flex-wrap: wrap; }
    .c-top a { color: var(--text); }
    blockquote { margin: .2rem 0; padding: .4rem .8rem; border-left: 3px solid var(--border-strong); color: var(--text-2);
      font-size: .9rem; background: var(--surface-sunken); border-radius: 0 6px 6px 0; overflow-wrap: anywhere; }
  `,
})
export class EvaluationPage {
  private readonly session = inject(SessionService);
  protected readonly metrics = httpResource<Metrics>(() => `${this.session.apiUrl()}/api/metrics`);
  protected readonly contested = httpResource<ContestedVerdict[]>(() => `${this.session.apiUrl()}/api/feedback`);

  protected precision(m: Metrics): string | null {
    const ok = m.avis_justes ?? 0;
    const total = ok + (m.avis_errones ?? 0);
    return total > 0 ? `${Math.round((ok / total) * 100)} %` : null;
  }

  protected decisionLabel(d: string | null): string {
    return d === 'VRAI_PROBLEME' ? 'vrai problème' : d === 'FAUX_POSITIF' ? 'fausse alerte' : d === 'A_VERIFIER' ? 'à vérifier' : 'non trié';
  }

  protected readonly goals: Goal[] = [
    { key: 'detection', label: 'Failles détectées', unit: '', goal: '≥ 11/12', ok: (v) => {
      const m = typeof v === 'string' ? /^(\d+)\/(\d+)$/.exec(v) : null;
      return m ? Number(m[1]) / Number(m[2]) >= 11 / 12 : null;
    } },
    { key: 'bruitEnMoinsPct', label: 'Bruit en moins', unit: ' %', goal: '≥ 70 %', ok: (v) => (num(v) === null ? null : num(v)! >= 70) },
    { key: 'faussesAlertesRestantesPct', label: 'Fausses alertes restantes', unit: ' %', goal: '≤ 15 %',
      ok: (v) => (num(v) === null ? null : num(v)! <= 15) },
    { key: 'tempsMoyenScanEtTriageS', label: 'Temps par PR', unit: ' s', goal: '< 90 s', ok: (v) => (num(v) === null ? null : num(v)! < 90) },
    { key: 'coutMoyenParPrUsd', label: 'Coût par PR', unit: ' $', goal: '< 0,01 $', ok: (v) => (num(v) === null ? null : num(v)! < 0.01) },
  ];

  protected format(value: Value, unit: string): string {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'number') return value.toLocaleString('fr-FR', { maximumFractionDigits: 4 }) + unit;
    return value + unit;
  }

  protected failles(cas: { attendu: string }[]): number {
    return cas.filter((c) => c.attendu === 'VRAI_PROBLEME').length;
  }

  protected detected(cas: { attendu: string; detecte: boolean }[]): number {
    return cas.filter((c) => c.attendu === 'VRAI_PROBLEME' && c.detecte).length;
  }
}
