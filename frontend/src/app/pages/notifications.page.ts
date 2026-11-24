import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource, HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../api.service';
import { NotificationAccount, Notifications } from '../models';
import { SessionService } from '../session.service';
import { Icon } from '../ui/icon';

const ERREURS: Record<string, string> = {
  'slack-url': 'Adresse invalide : collez l’URL d’un « Incoming Webhook » Slack (https://hooks.slack.com/services/…).',
  'slack-desactive': 'Slack n’est pas activé sur cette instance (clé de chiffrement absente).',
  emails: 'Adresses e-mail invalides (10 au plus).',
  'slack-non-configure': 'Enregistrez d’abord un webhook Slack.',
  'email-non-configure': 'Enregistrez d’abord au moins une adresse (et l’envoi d’e-mails doit être configuré).',
  'slack-echec': 'Slack a refusé le message : vérifiez le webhook.',
  'email-echec': 'L’envoi de l’e-mail a échoué.',
};

/** Notifications par compte GitHub : alerte Slack immédiate et rapport hebdomadaire par e-mail. */
@Component({
  selector: 'app-notifications',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Notifications</h1><p>Soyez prévenu d'une faille sans ouvrir la console.</p></div>
      </div>

      @if (data.error()) {
        <div class="card empty error" role="alert">Chargement impossible.</div>
      } @else if (data.value(); as d) {
        @if (d.comptes.length === 0) {
          <div class="card empty">
            <p><strong>Aucun compte à configurer.</strong></p>
            <p>Installez la GitHub App sur un compte ou une organisation, puis reconnectez-vous.</p>
          </div>
        }
        @for (c of d.comptes; track c.installationId) {
          <section class="card">
            <div class="card-head"><h2>{{ c.compte }}</h2></div>
            <div class="card-body grid two-cols">
              <!-- Slack -->
              <form class="channel" (submit)="saveSlack(c, $event)">
                <h3><app-icon name="bell" [size]="16" /> Alerte Slack immédiate</h3>
                @if (!d.slackActif) {
                  <p class="muted small">Non disponible sur cette instance (SENTINEL_ENCRYPTION_KEY absente).</p>
                } @else {
                  <p class="small text-2">Dans Slack : Apps → « Incoming Webhooks » → choisir le canal, puis coller l'URL ici.
                    Elle est chiffrée et n'est plus jamais affichée.</p>
                  @if (c.slackConfigure) {
                    <p class="small ok"><app-icon name="check" [size]="14" /> Webhook enregistré (…{{ c.slackIndice }})</p>
                  }
                  <div class="field">
                    <label [for]="'slack-' + c.installationId">{{ c.slackConfigure ? 'Remplacer le webhook' : 'URL du webhook' }}</label>
                    <input class="input mono" type="url" [id]="'slack-' + c.installationId" autocomplete="off"
                           placeholder="https://hooks.slack.com/services/…" [value]="slackDraft()[c.installationId] ?? ''"
                           (input)="setDraft(c, $event)" />
                  </div>
                  <fieldset class="levels">
                    <legend class="small text-2">Prévenir pour</legend>
                    <label><input type="radio" [name]="'lvl-' + c.installationId" [checked]="level(c) === 'CRITIQUE'"
                                  (change)="setLevel(c, 'CRITIQUE')" /> Les failles critiques (fusion bloquée)</label>
                    <label><input type="radio" [name]="'lvl-' + c.installationId" [checked]="level(c) === 'VRAI_PROBLEME'"
                                  (change)="setLevel(c, 'VRAI_PROBLEME')" /> Tout vrai problème</label>
                  </fieldset>
                  <div class="actions">
                    <button type="submit" class="btn primary" [disabled]="busy()">Enregistrer</button>
                    @if (c.slackConfigure) {
                      <button type="button" class="btn" [disabled]="busy()" (click)="test(c, 'slack')">Envoyer un test</button>
                      <button type="button" class="btn ghost" [disabled]="busy()" (click)="removeSlack(c)">Retirer</button>
                    }
                  </div>
                }
              </form>

              <!-- E-mail -->
              <form class="channel" (submit)="saveEmails(c, $event)">
                <h3><app-icon name="comment" [size]="16" /> Rapport hebdomadaire</h3>
                @if (!d.emailActif) {
                  <p class="muted small">Non disponible sur cette instance (envoi d'e-mails non configuré).</p>
                } @else {
                  <p class="small text-2">Chaque lundi : revues de la semaine, failles par gravité, fausses alertes écartées,
                    dépôts les plus exposés. Texte brut, sans pixel de suivi.</p>
                  <div class="field">
                    <label [for]="'emails-' + c.installationId">Destinataires (séparés par des virgules, 10 au plus)</label>
                    <textarea class="input" rows="2" [id]="'emails-' + c.installationId" autocomplete="off"
                              placeholder="equipe@exemple.fr, cto@exemple.fr"
                              [value]="emailDraft()[c.installationId] ?? c.emails.join(', ')" (input)="setEmails(c, $event)"></textarea>
                  </div>
                  <label class="check"><input type="checkbox" [checked]="weekly(c)" (change)="toggleWeekly(c, $event)" />
                    Envoyer le rapport chaque lundi</label>
                  <div class="actions">
                    <button type="submit" class="btn primary" [disabled]="busy()">Enregistrer</button>
                    @if (c.emails.length > 0) {
                      <button type="button" class="btn" [disabled]="busy()" (click)="test(c, 'email')">Recevoir le rapport maintenant</button>
                    }
                  </div>
                }
              </form>
            </div>
            @if (message()[c.installationId]; as m) {
              <p class="msg" [class.error]="m.bad" role="status">{{ m.text }}</p>
            }
          </section>
        }
      } @else {
        <div class="skeleton" style="height: 260px"></div>
      }
    </div>
  `,
  styles: `
    .small { font-size: .82rem; }
    .two-cols { grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem 2rem; }
    .channel { display: grid; gap: .75rem; align-content: start; min-width: 0; }
    .channel h3 { display: flex; align-items: center; gap: .45rem; }
    .channel h3 app-icon { color: var(--accent); }
    .ok { display: flex; align-items: center; gap: .35rem; }
    .ok app-icon { color: var(--good); }
    .levels { border: 0; padding: 0; margin: 0; display: grid; gap: .35rem; font-size: .9rem; }
    .levels legend { margin-bottom: .3rem; }
    .levels label, .check { display: flex; gap: .5rem; align-items: center; font-size: .9rem; }
    textarea.input { height: auto; padding: .55rem .75rem; resize: vertical; }
    .actions { display: flex; gap: .5rem; flex-wrap: wrap; }
    .msg { padding: 0 1.2rem 1rem; font-size: .88rem; color: var(--text-2); }
  `,
})
export class NotificationsPage {
  private readonly session = inject(SessionService);
  private readonly api = inject(ApiService);
  protected readonly data = httpResource<Notifications>(() => `${this.session.apiUrl()}/api/notifications`);
  protected readonly busy = signal(false);
  protected readonly slackDraft = signal<Record<number, string>>({});
  protected readonly emailDraft = signal<Record<number, string>>({});
  private readonly levels = signal<Record<number, 'CRITIQUE' | 'VRAI_PROBLEME'>>({});
  private readonly weeklies = signal<Record<number, boolean>>({});
  protected readonly message = signal<Record<number, { text: string; bad: boolean }>>({});

  protected level(c: NotificationAccount) {
    return this.levels()[c.installationId] ?? c.slackNiveau;
  }

  protected weekly(c: NotificationAccount): boolean {
    return this.weeklies()[c.installationId] ?? c.rapportHebdo;
  }

  protected setDraft(c: NotificationAccount, e: Event): void {
    this.slackDraft.update((m) => ({ ...m, [c.installationId]: (e.target as HTMLInputElement).value }));
  }

  protected setEmails(c: NotificationAccount, e: Event): void {
    this.emailDraft.update((m) => ({ ...m, [c.installationId]: (e.target as HTMLTextAreaElement).value }));
  }

  protected setLevel(c: NotificationAccount, l: 'CRITIQUE' | 'VRAI_PROBLEME'): void {
    this.levels.update((m) => ({ ...m, [c.installationId]: l }));
  }

  protected toggleWeekly(c: NotificationAccount, e: Event): void {
    this.weeklies.update((m) => ({ ...m, [c.installationId]: (e.target as HTMLInputElement).checked }));
  }

  protected saveSlack(c: NotificationAccount, e: Event): void {
    e.preventDefault();
    const url = (this.slackDraft()[c.installationId] ?? '').trim();
    this.save(c, { slackWebhook: url === '' ? null : url, slackNiveau: this.level(c) }, 'Alerte Slack enregistrée.');
  }

  protected removeSlack(c: NotificationAccount): void {
    this.save(c, { slackWebhook: '' }, 'Webhook Slack retiré.');
  }

  protected saveEmails(c: NotificationAccount, e: Event): void {
    e.preventDefault();
    const raw = this.emailDraft()[c.installationId];
    const emails = raw === undefined ? c.emails : raw.split(/[,;\s]+/).map((x) => x.trim()).filter(Boolean);
    this.save(c, { emails, rapportHebdo: this.weekly(c) }, 'Rapport hebdomadaire enregistré.');
  }

  protected test(c: NotificationAccount, channel: 'slack' | 'email'): void {
    this.busy.set(true);
    this.api.testNotification(c.installationId, channel).subscribe({
      next: () => this.done(c, channel === 'slack' ? 'Message de test envoyé dans Slack.' : 'Rapport envoyé.', false),
      error: (err: HttpErrorResponse) => this.done(c, this.explain(err), true),
    });
  }

  private save(c: NotificationAccount, body: Parameters<ApiService['saveNotifications']>[1], ok: string): void {
    this.busy.set(true);
    this.api.saveNotifications(c.installationId, body).subscribe({
      next: () => {
        this.slackDraft.update((m) => ({ ...m, [c.installationId]: '' }));
        this.done(c, ok, false);
        this.data.reload();
      },
      error: (err: HttpErrorResponse) => this.done(c, this.explain(err), true),
    });
  }

  private explain(err: HttpErrorResponse): string {
    if (err.status === 429) return 'Un test par minute au plus : réessayez dans un instant.';
    if (err.status === 403) return "Vous n'avez pas accès à ce compte GitHub.";
    return ERREURS[(err.error as { erreur?: string } | null)?.erreur ?? ''] ?? 'Opération impossible pour le moment.';
  }

  private done(c: NotificationAccount, text: string, bad: boolean): void {
    this.busy.set(false);
    this.message.update((m) => ({ ...m, [c.installationId]: { text, bad } }));
  }
}
