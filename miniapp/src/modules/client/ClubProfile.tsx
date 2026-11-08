import { useEffect, useRef, useState } from 'react';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useTelegramClosingConfirmation,
  useUnsavedChanges,
} from './OnboardingShared';
import { ClubProfile as Profile } from './onboarding.types';
import ClubSetup from './ClubSetup';
import { locationPresentation, timezoneLabel } from './profilePresentation';

type Fields = Pick<Profile, 'name' | 'description' | 'timezone'>;
const editableFields = ['name', 'description', 'timezone'] as const;
const fieldsOf = (profile: Profile): Fields => ({
  name: profile.name,
  description: profile.description,
  timezone: profile.timezone,
});
const capabilitiesOf = (profile: Profile) => [
  ...profile.permissions,
  ...profile.availableActions.map((action) => 'action:' + action),
  ...(profile.canPreviewBooking ? ['preview'] : []),
];

function countryName(code: string): string {
  try {
    return new Intl.DisplayNames(['ru'], { type: 'region' }).of(code) ?? code;
  } catch {
    return code;
  }
}

function validation(fields: Fields, savedTimezone: string): string | null {
  if (!fields.name.trim() || fields.name.trim().length > 255)
    return 'Укажите название клуба от 1 до 255 символов.';
  if (fields.description.length > 2000)
    return 'Описание должно содержать не более 2000 символов.';
  // A browser's older IANA catalog must not block saving an unchanged server zone.
  if (fields.timezone === savedTimezone) return null;
  // The server validates an explicit correction as the final authority.
  try {
    if (!fields.timezone.trim() || fields.timezone.trim().length > 64)
      throw new Error('Invalid timezone');
    new Intl.DateTimeFormat('ru-RU', { timeZone: fields.timezone.trim() });
  } catch {
    return 'Укажите часовой пояс IANA, например Europe/Moscow.';
  }
  return null;
}

type Props = {
  credential: string;
  id: string;
  home: (clientId?: string) => void;
  reportDirty: (dirty: boolean) => void;
  reportPending?: (pending: boolean) => void;
  openLayout?: () => void;
  openFinance?: () => void;
  openModules?: () => void;
  openTeam?: () => void;
  openCalendar?: () => void;
  openBookingRules?: () => void;
  openBookingPreview?: () => void;
};

export default function ClubProfile(props: Props) {
  return (
    <ClubProfileEditor key={props.credential + ':' + props.id} {...props} />
  );
}

function ClubProfileEditor({
  credential,
  id,
  home,
  reportDirty,
  reportPending,
  openLayout,
  openFinance,
  openModules,
  openTeam,
  openCalendar,
  openBookingRules,
  openBookingPreview,
}: Props) {
  const path = '/api/client/clubs/' + id + '/profile';
  const [profile, setProfile] = useState<Profile>();
  const [draft, setDraft] = useState<{ base: Profile; fields: Fields }>();
  const [validationError, setValidationError] = useState<string | null>(null);
  const [denied, setDenied] = useState(false);
  // Old responses stay fenced even after a fresh read restores access.
  const authority = useRef({
    denied: false,
    epoch: 0,
    capabilities: [] as string[],
  });
  const latest = useRef<Profile>();
  const clientId = useRef<string>();
  const cancelRead = useRef<() => void>(() => undefined);
  const profileFields = useRef<HTMLHeadingElement>(null);
  const deny = () => {
    authority.current = {
      denied: true,
      epoch: authority.current.epoch + 1,
      capabilities: [],
    };
    cancelRead.current();
    latest.current = undefined;
    setDenied(true);
    setProfile(undefined);
    setDraft(undefined);
    setValidationError(null);
  };
  const checkProfile = (received: Profile) => {
    if (
      received.id !== id ||
      (clientId.current && received.clientId !== clientId.current) ||
      !received.permissions.includes('client.club.read') ||
      !/^[1-9][0-9]*$/.test(received.version)
    )
      throw new OnboardingError(404, 'not_found');
  };
  const onError = (error: unknown) => {
    if (authorityLost(error)) deny();
  };
  const remote = useRemote<Profile>(credential, path, undefined, {
    retainOnForeground: true,
    onError,
    onSuccess: (received) => {
      checkProfile(received);
      clientId.current = received.clientId;
      const capabilities = capabilitiesOf(received);
      if (
        authority.current.capabilities.some(
          (capability) => !capabilities.includes(capability),
        )
      )
        authority.current.epoch++;
      authority.current.denied = false;
      authority.current.capabilities = capabilities;
      setDenied(false);
      if (
        !latest.current ||
        BigInt(received.version) >= BigInt(latest.current.version)
      ) {
        latest.current = received;
      } else {
        // Configuration versions do not version grants. A fresh read's authority
        // applies even when its configuration snapshot is older than a command.
        latest.current = {
          ...latest.current,
          permissions: received.permissions,
          availableActions: received.availableActions,
          canPreviewBooking: received.canPreviewBooking,
        };
      }
      setProfile(latest.current);
    },
  });
  cancelRead.current = remote.cancel;
  const command = useCommand<Profile>(credential, { onError });
  // A refused club must not trap someone who can still use other clubs.
  // Staying here retains the original command for a later authorized replay.
  const navigationBlocked = command.blocked && !denied;
  useEffect(() => {
    reportPending?.(navigationBlocked);
    return () => reportPending?.(false);
  }, [navigationBlocked, reportPending]);
  const versionConflict =
    command.error instanceof OnboardingError &&
    command.error.code === 'version_conflict';
  const reload = remote.refresh;
  useEffect(() => {
    if (versionConflict && !denied) reload();
  }, [versionConflict, reload, denied]);
  useEffect(() => {
    if (!denied && authorityLost(command.error)) command.reset();
  }, [denied, command]);
  const fields = draft?.fields ?? (profile && fieldsOf(profile));
  const dirty = Boolean(draft);
  const conflict =
    versionConflict ||
    Boolean(draft && profile && draft.base.version !== profile.version);
  const leave = useUnsavedChanges(dirty || command.unknown, reportDirty);
  useTelegramClosingConfirmation(dirty || command.saving || command.unknown);
  const edit = (base: Profile, changes: Partial<Fields>) => {
    setDraft((current) => {
      const original = current?.base ?? base;
      const initial = fieldsOf(original);
      const next = { ...(current?.fields ?? initial), ...changes };
      return JSON.stringify(initial) === JSON.stringify(next)
        ? undefined
        : { base: original, fields: next };
    });
  };
  const accept = (result: Profile, epoch: number) => {
    if (authority.current.denied || authority.current.epoch !== epoch)
      return false;
    checkProfile(result);
    if (
      latest.current &&
      BigInt(latest.current.version) > BigInt(result.version)
    ) {
      reload();
      return false;
    }
    latest.current = result;
    setProfile(result);
    setDraft(undefined);
    setValidationError(null);
    reload();
    return true;
  };
  const canWrite = Boolean(
    profile?.permissions.includes('client.club.read') &&
      profile.permissions.includes('client.club.profile.write') &&
      profile.availableActions.includes('update'),
  );
  const editedFields = draft
    ? editableFields.filter(
        (field) => draft.fields[field] !== draft.base[field],
      )
    : [];
  const canReapply =
    canWrite &&
    !command.blocked &&
    !remote.loading &&
    !remote.error &&
    draft &&
    profile &&
    draft.base.version !== profile.version &&
    editedFields.every(
      (field) =>
        profile[field] === draft.base[field] ||
        profile[field] === draft.fields[field],
    );
  const reapply = () => {
    if (!canReapply || !draft || !profile) return;
    const next = fieldsOf(profile);
    for (const field of editedFields) next[field] = draft.fields[field];
    setDraft(
      editedFields.some((field) => next[field] !== profile[field])
        ? { base: profile, fields: next }
        : undefined,
    );
    command.reset();
    setValidationError(null);
  };
  const refresh = () => {
    if (command.blocked || !leave()) return;
    // Only an authoritative command replay resolves an unknown mutation result.
    // Reloading the profile never creates a new intent or abandons its key.
    command.reset();
    setDraft(undefined);
    setValidationError(null);
    remote.refresh();
  };
  const back = () => {
    if (!navigationBlocked && leave())
      home(denied ? undefined : profile?.clientId);
  };
  const openSection = (open?: () => void) =>
    open &&
    (() => {
      if (!command.blocked && leave()) open();
    });
  const location =
    profile && locationPresentation(profile.city, profile.region);
  return (
    <Shell
      title={(!denied && profile?.name) || 'Профиль клуба'}
      subtitle="Профиль заведения"
      back={back}
      backDisabled={navigationBlocked}
      backLabel="Организация и другие заведения"
    >
      <RemoteState {...remote} />
      {denied && !remote.error && (
        <div role="alert">
          <p>
            Доступ утрачен или запись недоступна. Вернитесь в кабинет и обновите
            данные.
          </p>
          <button type="button" className="ob-button" onClick={reload}>
            Повторить загрузку
          </button>
        </div>
      )}
      {profile && fields && !denied && (
        <>
          <div className="po-context">
            <span>
              Организация: <strong>{profile.clientName}</strong>
            </span>
            <span className="po-context-badge">Не опубликован</span>
          </div>
          <section className="po-card ob-card">
            <h2>Сохранённое заведение</h2>
            <dl className="po-details">
              {[
                ['Страна', countryName(profile.countryCode)],
                ['Город', location?.city],
                ...(location?.region ? [['Регион', location.region]] : []),
                ['Адрес', profile.address],
                ...(profile.unit.trim() ? [['Помещение', profile.unit]] : []),
                ['Часовой пояс (сейчас)', timezoneLabel(profile.timezone)],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value || '—'}</dd>
                </div>
              ))}
            </dl>
            <p className="po-muted">
              Часовой пояс сохранён в профиле заведения. Если страна, город или
              адрес указаны неверно, обратитесь к владельцу платформы для
              уточнения местоположения.
            </p>
          </section>
          {profile.id === id &&
            profile.permissions.includes('client.club.read') && (
              <ClubSetup
                key={profile.clientId + ':' + id + ':' + profile.timezone}
                credential={credential}
                profile={profile}
                navigationBlocked={command.blocked}
                openProfile={
                  canWrite ? () => profileFields.current?.focus() : undefined
                }
                openFinance={openSection(openFinance)}
                openModules={openSection(openModules)}
                openTeam={openSection(openTeam)}
                openLayout={openSection(openLayout)}
                openCalendar={openSection(openCalendar)}
                openBookingRules={openSection(openBookingRules)}
                openBookingPreview={openSection(openBookingPreview)}
              />
            )}
          <h2 tabIndex={-1} ref={profileFields}>
            Настройки профиля
          </h2>
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (
                !canWrite ||
                command.blocked ||
                conflict ||
                remote.loading ||
                remote.error ||
                !draft
              )
                return;
              const problem = validation(fields, draft.base.timezone);
              setValidationError(problem);
              if (problem) return;
              const epoch = authority.current.epoch;
              command.run(
                path,
                { ...fields, expectedVersion: draft.base.version },
                (result) => accept(result, epoch),
                'PATCH',
              );
            }}
          >
            <fieldset
              className="ob-fields"
              disabled={
                !canWrite ||
                command.blocked ||
                conflict ||
                remote.loading ||
                Boolean(remote.error)
              }
            >
              <Field label="Отображаемое название заведения">
                <input
                  value={fields.name}
                  maxLength={255}
                  required
                  onChange={(event) =>
                    edit(profile, { name: event.target.value })
                  }
                />
              </Field>
              <p className="po-muted">
                Это название в списке «Мои клубы». Название организации не
                изменится.
              </p>
              <Field label="Описание клуба">
                <textarea
                  value={fields.description}
                  maxLength={2000}
                  rows={5}
                  onChange={(event) =>
                    edit(profile, { description: event.target.value })
                  }
                />
              </Field>
              {canWrite && (
                <details className="ob-timezone-correction">
                  <summary>Исправить часовой пояс</summary>
                  <p className="po-muted">
                    Только если сохранённый пояс неверен. Уточните IANA-код у
                    владельца платформы. Это исправление не меняет город или
                    адрес и применяется после сохранения профиля.
                  </p>
                  <Field label="Часовой пояс IANA">
                    <input
                      value={fields.timezone}
                      maxLength={64}
                      autoCapitalize="none"
                      spellCheck={false}
                      onChange={(event) =>
                        edit(profile, { timezone: event.target.value })
                      }
                    />
                  </Field>
                </details>
              )}
            </fieldset>
            {!canWrite && (
              <p className="po-muted">Профиль доступен только для просмотра.</p>
            )}
            {validationError && (
              <p className="po-notice" role="alert">
                {validationError}
              </p>
            )}
            <p className="po-muted">
              Последнее успешное сохранение:{' '}
              <time dateTime={profile.savedAt}>
                {new Date(profile.savedAt).toLocaleString('ru-RU')}
              </time>
            </p>
            {canWrite && (
              <button
                className="po-primary"
                type="submit"
                disabled={
                  command.blocked ||
                  conflict ||
                  remote.loading ||
                  Boolean(remote.error) ||
                  !dirty
                }
              >
                Сохранить профиль
              </button>
            )}
          </form>
          {conflict && (
            <section className="po-notice" role="status">
              <p>
                Профиль изменился. Ваш черновик сохранён на экране.
                {canReapply
                  ? ' Вы можете перенести свои правки на актуальную версию и проверить их перед сохранением.'
                  : ' Чтобы начать с актуальной версии, загрузите профиль и подтвердите сброс черновика.'}
              </p>
              {canReapply && (
                <button type="button" className="ob-button" onClick={reapply}>
                  Перенести мои правки на актуальный профиль
                </button>
              )}
            </section>
          )}
          <button
            className="ob-button"
            onClick={refresh}
            disabled={command.blocked}
          >
            Загрузить актуальный профиль
          </button>
        </>
      )}
      {!denied && (
        <CommandState
          command={{
            ...command,
            retry: () => {
              const epoch = authority.current.epoch;
              command.retryWith((result) => accept(result, epoch));
            },
          }}
          refresh={command.unknown ? undefined : refresh}
        />
      )}
    </Shell>
  );
}
