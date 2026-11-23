import { Component, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Metrics } from '../models';
import { SessionService } from '../session.service';

@Component({
  selector: 'app-metrics',
  imports: [DecimalPipe, DatePipe],
  template: `
    <h1>Métriques</h1>
    @if (metrics.error()) {
      <p class="error" role="alert">Chargement impossible.</p>
    } @else if (metrics.value(); as m) {
      <div class="tiles">
        <div class="tile"><span class="value">{{ m.revues }}</span><span class="label">revues</span></div>
        <div class="tile"><span class="value">{{ m.vrais_problemes }}</span><span class="label">vrais problèmes</span></div>
        <div class="tile"><span class="value">{{ m.faux_positifs }}</span><span class="label">faux positifs écartés</span></div>
        <div class="tile"><span class="value">{{ m.cout_moyen_usd | number: '1.4-4' }} $</span><span class="label">coût moyen par revue</span></div>
        <div class="tile"><span class="value">{{ m.duree_moyenne_s === null ? '—' : (m.duree_moyenne_s | number: '1.0-0') + ' s' }}</span>
          <span class="label">durée moyenne</span></div>
      </div>

      <h2>Évaluation (banc de 20 PR)</h2>
      @if (m.evaluation; as e) {
        <p class="muted">{{ e.date | date: 'medium' }} · mode {{ e.mode }}{{ e.modele ? ' · ' + e.modele : '' }}</p>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Métrique</th><th>Mesure</th><th>Objectif</th></tr></thead>
            <tbody>
              @for (row of rows; track row.key) {
                <tr><td>{{ row.label }}</td><td>{{ format(e.metriques[row.key], row.unit) }}</td>
                  <td>{{ row.goal }}</td></tr>
              }
            </tbody>
          </table>
        </div>
        <details><summary>Détail des {{ e.cas.length }} cas</summary>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Cas</th><th>Attendu</th><th>Alertes</th><th>Vrais</th><th>À vérifier</th><th>FP</th><th>Détecté</th></tr></thead>
              <tbody>
                @for (c of e.cas; track c.id) {
                  <tr><td>{{ c.titre }}</td><td>{{ c.attendu }}</td><td>{{ c.alertesLignesModifiees }}</td>
                    <td>{{ c.vraisProblemes }}</td><td>{{ c.aVerifier }}</td><td>{{ c.fauxPositifs }}</td>
                    <td>{{ c.detecte ? 'oui' : 'non' }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </details>
      } @else {
        <p class="muted">Aucun rapport d'évaluation publié (eval/report.json).</p>
      }
    } @else {
      <p class="muted">Chargement…</p>
    }
  `,
})
export class MetricsPage {
  private readonly session = inject(SessionService);
  protected readonly metrics = httpResource<Metrics>(() => `${this.session.apiUrl()}/api/metrics`);

  /** Métriques du plan, dans l'ordre du plan. */
  protected readonly rows = [
    { key: 'detection', label: 'Détection (failles trouvées)', unit: '', goal: '≥ 11/12' },
    { key: 'bruitEnMoinsPct', label: 'Bruit en moins (faux positifs écartés)', unit: '%', goal: '≥ 70 %' },
    { key: 'faussesAlertesRestantesPct', label: 'Fausses alertes restantes', unit: '%', goal: '≤ 15 %' },
    { key: 'tempsMoyenScanEtTriageS', label: 'Temps par PR (scan + triage)', unit: ' s', goal: '< 90 s' },
    { key: 'coutMoyenParPrUsd', label: 'Coût par PR', unit: ' $', goal: '< 0,01 $' },
    { key: 'alertesScannersSurPieges', label: 'Alertes des scanners sur les pièges', unit: '', goal: '' },
  ];

  format(value: string | number | null | undefined, unit: string): string {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'number') return value.toLocaleString('fr-FR', { maximumFractionDigits: 4 }) + unit;
    return value + unit;
  }
}
