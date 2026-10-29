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

function validation(fields: Fields): string | null {
  if (!fields.name.trim() || fields.name.trim().length > 255)
    return 'Укажите название клуба от 1 до 255 символов.';
  if (fields.description.length > 2000)
    return 'Описание должно содержать не более 2000 символов.';
  // The server validates against its IANA catalog as the final authority.
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
}: {
  credential: string;
  id: string;
  home: () => void;
  reportDirty: (dirty: boolean) => void;
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
    if (leave()) home();
  };
  return (
    <Shell title={profile?.name ?? 'Профиль клуба'} back={back}>
      <RemoteState {...remote} />
      {profile && fields && !remote.loading && (
        <>
          <div className="po-context">
            <strong>{profile.clientName}</strong>
            <span className="po-context-badge">Непубличный</span>
          </div>
          <p className="po-notice">Бот не подключён</p>
          <section className="po-card ob-card">
            <h2>Сохранённое заведение</h2>
            <dl className="po-details">
              {[
                ['Страна', countryName(profile.countryCode)],
                ['Город', profile.city],
                ...(profile.region ? [['Регион', profile.region]] : []),
                ['Адрес', profile.address],
                ['Помещение', profile.unit],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value || '—'}</dd>
                </div>
              ))}
            </dl>
            <p className="po-muted">
              Эти сведения сохранены в профиле заведения.
            </p>
          </section>
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!canWrite || command.blocked || conflict || !dirty) return;
              const problem = validation(fields);
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
              <Field label="Отображаемое название">
                <input
                  value={fields.name}
                  maxLength={255}
                  required
                  onChange={(event) =>
                    setFields({ ...fields, name: event.target.value })
                  }
                />
              </Field>
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
              <Field label="Часовой пояс">
                <input
                  value={fields.timezone}
                  maxLength={64}
                  placeholder="Europe/Moscow"
                  required
                  onChange={(event) =>
                    setFields({ ...fields, timezone: event.target.value })
                  }
                />
              </Field>
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
