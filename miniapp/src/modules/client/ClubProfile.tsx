import { useEffect, useRef, useState } from 'react';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';
import { ClubProfile as Profile } from './onboarding.types';
import { locationPresentation, timezoneLabel } from './profilePresentation';

type Fields = Pick<Profile, 'name' | 'description' | 'timezone'>;
const fieldsOf = (profile: Profile): Fields => ({
  name: profile.name,
  description: profile.description,
  timezone: profile.timezone,
});

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

export default function ClubProfile({
  credential,
  id,
  home,
  reportDirty,
  openLayout,
}: {
  credential: string;
  id: string;
  home: (clientId?: string) => void;
  reportDirty: (dirty: boolean) => void;
  openLayout?: () => void;
}) {
  const path = '/api/client/clubs/' + id + '/profile';
  const remote = useRemote<Profile>(credential, path);
  const command = useCommand<Profile>(credential);
  const [profile, setProfile] = useState<Profile>();
  const [fields, setFields] = useState<Fields>();
  const [validationError, setValidationError] = useState<string | null>(null);
  const lastRemote = useRef<Profile>();
  const conflict =
    command.error instanceof OnboardingError && command.error.status === 409;
  const reload = remote.refresh;

  useEffect(() => {
    if (authorityLost(command.error) || remote.error) {
      setProfile(undefined);
      setFields(undefined);
    } else if (remote.data && remote.data !== lastRemote.current) {
      lastRemote.current = remote.data;
      setProfile(remote.data);
      // A conflict refresh must not silently replace the user's unsaved input.
      setFields((current) => current ?? fieldsOf(remote.data!));
    }
  }, [remote.data, remote.error, command.error]);
  useEffect(() => {
    if (conflict) reload();
  }, [conflict, reload]);
  const dirty = Boolean(
    profile &&
      fields &&
      JSON.stringify(fields) !== JSON.stringify(fieldsOf(profile)),
  );
  const leave = useUnsavedChanges(dirty || command.unknown, reportDirty);
  const accept = (result: Profile) => {
    setProfile(result);
    setFields(fieldsOf(result));
    setValidationError(null);
  };
  const canWrite = Boolean(
    profile?.permissions.includes('client.club.read') &&
      profile.permissions.includes('client.club.profile.write') &&
      profile.availableActions.includes('update'),
  );
  const refresh = () => {
    if (!leave()) return;
    // Only an authoritative command replay resolves an unknown mutation result.
    // Reloading the profile never creates a new intent or abandons its key.
    if (!command.unknown) command.reset();
    setProfile(undefined);
    setFields(undefined);
    setValidationError(null);
    remote.refresh();
  };
  const back = () => {
    if (leave()) home(profile?.clientId);
  };
  const location =
    profile && locationPresentation(profile.city, profile.region);
  return (
    <Shell
      title={profile?.name ?? 'Профиль клуба'}
      subtitle="Профиль заведения"
      back={back}
    >
      <RemoteState {...remote} />
      {profile && fields && !remote.loading && (
        <>
          <div className="po-context">
            <span>
              Организация: <strong>{profile.clientName}</strong>
            </span>
            <span className="po-context-badge">Непубличный</span>
          </div>
          <p className="po-notice">Бот не подключён</p>
          <p className="po-muted">
            Night Concierge открывает доступ к платформе. Отдельный
            брендированный бот клуба подключается отдельно.
          </p>
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
          {openLayout && profile.permissions.includes('client.club.read') && (
            <button
              className="po-card ob-card ob-row"
              onClick={() => {
                if (leave()) openLayout();
              }}
            >
              <strong>Залы и столы</strong>
              <small>
                {profile.permissions.includes('client.club.layout.write')
                  ? 'Настроить схему и столы'
                  : 'Просмотреть схему и столы'}
              </small>
            </button>
          )}
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!canWrite || command.blocked || conflict || !dirty) return;
              const problem = validation(fields, profile.timezone);
              setValidationError(problem);
              if (problem) return;
              command.run(
                path,
                { ...fields, expectedVersion: profile.version },
                accept,
                'PATCH',
              );
            }}
          >
            <fieldset
              className="ob-fields"
              disabled={!canWrite || command.blocked || conflict}
            >
              <Field label="Отображаемое название заведения">
                <input
                  value={fields.name}
                  maxLength={255}
                  required
                  onChange={(event) =>
                    setFields({ ...fields, name: event.target.value })
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
                    setFields({ ...fields, description: event.target.value })
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
                        setFields({ ...fields, timezone: event.target.value })
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
                disabled={command.blocked || conflict || !dirty}
              >
                Сохранить профиль
              </button>
            )}
          </form>
          {conflict && (
            <p className="po-muted">
              Актуальный профиль перечитан. Ваш ввод сохранён на экране;
              загрузите актуальные данные перед новым сохранением.
            </p>
          )}
          <button
            className="ob-button"
            onClick={refresh}
            disabled={command.saving}
          >
            Загрузить актуальный профиль
          </button>
        </>
      )}
      <CommandState command={command} refresh={refresh} />
      <button className="ob-button" onClick={back}>
        В «Мои клубы»
      </button>
    </Shell>
  );
}
