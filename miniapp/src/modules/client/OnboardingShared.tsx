import { useEffect, useRef } from 'react';
import { ArrowLeft } from 'lucide-react';
import { useTelegram } from '../../app/providers/TelegramProvider';
import { errorMessage } from './onboarding.api';
import {
  applicationLabels,
  ClubApplication,
  VenueFields,
} from './onboarding.types';
import '../platform-owner/platform-owner.css';
import './onboarding.css';

export function Shell({
  title,
  subtitle,
  owner = false,
  back,
  children,
  navigation,
}: {
  title: string;
  subtitle?: string;
  owner?: boolean;
  back?: () => void;
  children: React.ReactNode;
  navigation?: React.ReactNode;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const webApp = useTelegram();
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, [title]);
  useEffect(() => {
    if (!back) return;
    webApp.BackButton?.show();
    webApp.BackButton?.onClick(back);
    return () => {
      webApp.BackButton?.offClick(back);
      webApp.BackButton?.hide();
    };
  }, [back, webApp]);
  return (
    <div className="platform-owner" lang="ru">
      <div className="po-container">
        <header className="po-header">
          <div className="po-brand">
            <span className="po-brand-symbol" aria-hidden="true">
              n<span>c</span>
            </span>
            <span>Night Concierge</span>
          </div>
          <span className="po-preview">
            {owner ? 'Владелец платформы' : 'Кабинет клиента'}
          </span>
        </header>
        <main className="po-main">
          {back && (
            <button className="po-back" type="button" onClick={back}>
              <ArrowLeft size={18} aria-hidden="true" /> Назад
            </button>
          )}
          <div className="po-page-title">
            <p className="po-eyebrow">
              {owner ? 'Управление платформой' : 'Мои клубы'}
            </p>
            <h1 ref={heading} tabIndex={-1}>
              {title}
            </h1>
            {subtitle && <p>{subtitle}</p>}
          </div>
          {children}
        </main>
        {navigation}
      </div>
    </div>
  );
}

export function RemoteState({
  loading,
  error,
  refresh,
}: {
  loading: boolean;
  error?: unknown;
  refresh: () => void;
}) {
  if (loading)
    return (
      <p className="po-notice" role="status" aria-busy="true">
        Загрузка…
      </p>
    );
  if (!error) return null;
  return (
    <div className="po-notice" role="alert">
      <div>
        <p>{errorMessage(error)}</p>
        <button className="ob-button" onClick={refresh}>
          Повторить загрузку
        </button>
      </div>
    </div>
  );
}

export function CommandState({
  command,
  refresh,
}: {
  command: {
    saving: boolean;
    unknown: boolean;
    success: boolean;
    error?: unknown;
    retry: () => void;
  };
  refresh?: () => void;
}) {
  if (command.saving)
    return (
      <p role="status" aria-busy="true">
        Сохраняем…
      </p>
    );
  if (command.unknown)
    return (
      <div className="po-notice" role="alert">
        <div>
          <h2>Результат операции пока неизвестен</h2>
          <p>Ответ не получен. Повтор той же команды безопасен.</p>
          <button className="ob-button" onClick={command.retry}>
            Повторить ту же операцию
          </button>
          {refresh && (
            <button className="ob-button" onClick={refresh}>
              Проверить состояние
            </button>
          )}
        </div>
      </div>
    );
  if (command.error)
    return (
      <div className="po-notice" role="alert">
        <div>
          <p>{errorMessage(command.error)}</p>
          {refresh && (
            <button className="ob-button" onClick={refresh}>
              Обновить данные
            </button>
          )}
        </div>
      </div>
    );
  if (command.success)
    return (
      <p className="po-notice" role="status">
        Сохранено
      </p>
    );
  return null;
}

export function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="ob-field">
      <span>{label}</span>
      {children}
    </label>
  );
}
export const blankVenue: VenueFields = {
  venueName: '',
  countryCode: '',
  city: '',
  address: '',
  unit: '',
  timezone: '',
  comment: '',
};
export function VenueForm({
  value,
  onChange,
  disabled,
}: {
  value: VenueFields;
  onChange: (value: VenueFields) => void;
  disabled?: boolean;
}) {
  const update = (key: keyof VenueFields, text: string) =>
    onChange({ ...value, [key]: text });
  return (
    <fieldset disabled={disabled} className="ob-fields">
      <Field label="Название заведения">
        <input
          value={value.venueName}
          onChange={(event) => update('venueName', event.target.value)}
          maxLength={255}
          required
        />
      </Field>
      <Field label="Код страны (2 буквы)">
        <input
          value={value.countryCode}
          onChange={(event) =>
            update('countryCode', event.target.value.toUpperCase())
          }
          maxLength={2}
          placeholder="RU"
          required
        />
      </Field>
      <Field label="Город">
        <input
          value={value.city}
          onChange={(event) => update('city', event.target.value)}
          maxLength={128}
          required
        />
      </Field>
      <Field label="Адрес">
        <input
          value={value.address}
          onChange={(event) => update('address', event.target.value)}
          maxLength={512}
          required
        />
      </Field>
      <Field label="Помещение / корпус">
        <input
          value={value.unit}
          onChange={(event) => update('unit', event.target.value)}
          maxLength={128}
        />
      </Field>
      <Field label="Часовой пояс">
        <input
          value={value.timezone}
          onChange={(event) => update('timezone', event.target.value)}
          maxLength={64}
          placeholder="Europe/Moscow"
          required
        />
      </Field>
      <Field label="Комментарий">
        <textarea
          value={value.comment}
          onChange={(event) => update('comment', event.target.value)}
          maxLength={2000}
          rows={4}
        />
      </Field>
    </fieldset>
  );
}
export function venueFields(application: VenueFields): VenueFields {
  return {
    venueName: application.venueName,
    countryCode: application.countryCode,
    city: application.city,
    address: application.address,
    unit: application.unit,
    timezone: application.timezone,
    comment: application.comment,
  };
}
export function ApplicationSummary({
  application,
}: {
  application: ClubApplication;
}) {
  return (
    <>
      <div className="po-context">
        <strong>{applicationLabels[application.status]}</strong>
        <span className="po-context-badge">{application.clientName}</span>
      </div>
      <section className="po-card ob-card">
        <h2>{application.venueName || 'Без названия'}</h2>
        <dl className="po-details">
          {[
            ['Страна', application.countryCode],
            ['Город', application.city],
            ['Адрес', application.address],
            ['Помещение', application.unit],
            ['Часовой пояс', application.timezone],
            ['Комментарий', application.comment],
          ].map(([label, text]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{text || '—'}</dd>
            </div>
          ))}
        </dl>
      </section>
      {application.decisionReason && (
        <p className="po-notice">{application.decisionReason}</p>
      )}
      {application.status === 'approved' && (
        <p className="po-notice">
          {application.createdClubId
            ? 'Подключение одобрено. Клуб создан по этой заявке.'
            : 'Подключение одобрено.'}
        </p>
      )}
      <section className="ob-section">
        <h2>История заявки</h2>
        <ol className="ob-history">
          {application.history.map((entry, index) => (
            <li key={index}>
              <strong>{applicationLabels[entry.status]}</strong>
              <time dateTime={entry.at}>
                {new Date(entry.at).toLocaleString('ru-RU')}
              </time>
              {entry.reason && <p>{entry.reason}</p>}
            </li>
          ))}
        </ol>
      </section>
    </>
  );
}
export function useUnsavedChanges(
  dirty: boolean,
  reportDirty?: (dirty: boolean) => void,
) {
  useEffect(() => {
    reportDirty?.(dirty);
    return () => reportDirty?.(false);
  }, [dirty, reportDirty]);
  useEffect(() => {
    if (!dirty) return;
    const prevent = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', prevent);
    return () => window.removeEventListener('beforeunload', prevent);
  }, [dirty]);
  return () =>
    !dirty ||
    window.confirm('Есть несохранённые изменения. Уйти без сохранения?');
}
