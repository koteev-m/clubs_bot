import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  Building2,
  Clock3,
  Globe2,
  LayoutGrid,
  LockKeyhole,
  Send,
  Settings,
  UserPlus,
  Users,
} from 'lucide-react';
import { useTelegram } from '../../app/providers/TelegramProvider';
import { PLATFORM_INVITE_PATH, PLATFORM_PREVIEW_PATH } from './previewAccess';
import './platform-owner.css';

const navigation = [
  { label: 'Обзор', icon: LayoutGrid, available: true },
  { label: 'Клубы', icon: Building2, available: false },
  { label: 'Доступы', icon: Users, available: false },
  { label: 'Настройки', icon: Settings, available: false },
];

/** Shared presentation; live entry is mounted only after the server access check. */
export default function PlatformOwnerScreens({
  preview = false,
}: {
  preview?: boolean;
}) {
  const webApp = useTelegram();
  const isInvitation = useCallback(
    () =>
      preview
        ? window.location.pathname === PLATFORM_INVITE_PATH
        : new URLSearchParams(window.location.search).get('screen') ===
          'invite',
    [preview],
  );
  const screenUrl = useCallback(
    (invite: boolean) => {
      if (preview) return invite ? PLATFORM_INVITE_PATH : PLATFORM_PREVIEW_PATH;
      const url = new URL(window.location.href);
      if (invite) url.searchParams.set('screen', 'invite');
      else url.searchParams.delete('screen');
      return url;
    },
    [preview],
  );
  const [inviting, setInviting] = useState(isInvitation);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    // A directly opened invite gets a real overview entry, so Back never invents a new forward entry.
    if (
      isInvitation() &&
      window.history.state?.platformOwnerScreen !== 'invite'
    ) {
      window.history.replaceState(
        { platformOwnerScreen: 'overview' },
        '',
        screenUrl(false),
      );
      window.history.pushState(
        { platformOwnerScreen: 'invite' },
        '',
        screenUrl(true),
      );
    }
    const onPopState = () => setInviting(isInvitation());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [isInvitation, screenUrl]);

  const back = useCallback(() => window.history.back(), []);

  useEffect(() => {
    if (!inviting) return;
    const button = webApp.BackButton;
    button?.show();
    button?.onClick(back);
    return () => {
      button?.offClick(back);
      button?.hide();
    };
  }, [back, inviting, webApp]);

  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, [inviting]);

  const openInvitation = () => {
    window.history.pushState(
      { platformOwnerScreen: 'invite' },
      '',
      screenUrl(true),
    );
    setInviting(true);
  };

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
            {preview ? 'Локальный просмотр' : 'Владелец платформы'}
          </span>
        </header>
        <main className="po-main">
          {inviting ? (
            <>
              <button className="po-back" type="button" onClick={back}>
                <ArrowLeft size={18} aria-hidden="true" /> Назад к обзору
              </button>
              <div className="po-page-title">
                <p className="po-eyebrow">Платформа / Доступы</p>
                <h1 ref={heading} tabIndex={-1}>
                  Пригласить владельца
                </h1>
                <p>
                  Человек получит доступ для создания и настройки собственного
                  клуба.
                </p>
              </div>
              <section
                className="po-card po-invite-card"
                aria-labelledby="invite-details"
              >
                <div className="po-icon po-icon-accent">
                  <UserPlus size={25} aria-hidden="true" />
                </div>
                <h2 id="invite-details">Приглашение в платформу</h2>
                <p className="po-muted">Отдельный клуб. Собственная команда.</p>
                <dl className="po-details">
                  <div>
                    <dt>Роль</dt>
                    <dd>Владелец клуба</dd>
                  </div>
                  <div>
                    <dt>Назначение</dt>
                    <dd>Новый клуб</dd>
                  </div>
                  <div>
                    <dt>Канал приглашения</dt>
                    <dd>
                      <Send size={15} aria-hidden="true" /> Telegram
                    </dd>
                  </div>
                </dl>
              </section>
              <section className="po-next" aria-labelledby="invite-next">
                <h2 id="invite-next">Что будет дальше</h2>
                <ol>
                  <li>
                    <span>01</span>
                    <div>
                      <h3>Принять приглашение</h3>
                      <p>Будущий владелец откроет приглашение в Telegram.</p>
                    </div>
                  </li>
                  <li>
                    <span>02</span>
                    <div>
                      <h3>Настроить свой клуб</h3>
                      <p>
                        Самостоятельно заполнит профиль и подготовит клуб к
                        работе.
                      </p>
                    </div>
                  </li>
                </ol>
              </section>
              <div className="po-notice" role="status">
                <LockKeyhole size={20} aria-hidden="true" />
                <div>
                  <h2>Создание приглашений ещё не подключено</h2>
                  <p>
                    Сейчас можно посмотреть экран. Приглашение не создаётся,
                    доступ никому не выдаётся.
                  </p>
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="po-page-title">
                <p className="po-eyebrow">Кабинет владельца</p>
                <h1 ref={heading} tabIndex={-1}>
                  Управление платформой
                </h1>
                <p>Клубы и доступы в одном месте.</p>
              </div>
              <div className="po-context">
                <span className="po-icon">
                  <Globe2 size={21} aria-hidden="true" />
                </span>
                <div>
                  <span className="po-muted">Текущий контекст</span>
                  <strong>Платформа</strong>
                </div>
                <span className="po-context-badge">Обзор</span>
              </div>
              <section className="po-overview" aria-labelledby="clubs-overview">
                <div className="po-section-heading">
                  <h2 id="clubs-overview">Обзор клубов</h2>
                  <span>Нет данных</span>
                </div>
                <p className="po-muted">Данные платформы ещё не подключены.</p>
                <dl className="po-stats">
                  <div className="po-card">
                    <Clock3 size={20} aria-hidden="true" />
                    <dt>Клубы в процессе настройки</dt>
                    <dd>
                      <span aria-hidden="true">—</span>
                      <span className="po-sr-only">Данные не подключены</span>
                    </dd>
                  </div>
                  <div className="po-card">
                    <Users size={20} aria-hidden="true" />
                    <dt>Ожидающие приглашения</dt>
                    <dd>
                      <span aria-hidden="true">—</span>
                      <span className="po-sr-only">Данные не подключены</span>
                    </dd>
                  </div>
                </dl>
              </section>
              <button
                className="po-primary"
                type="button"
                onClick={openInvitation}
              >
                <UserPlus size={21} aria-hidden="true" />
                <span>Пригласить владельца</span>
                <ArrowUpRight size={21} aria-hidden="true" />
              </button>
              <section className="po-clubs" aria-labelledby="clubs-list">
                <div className="po-section-heading">
                  <h2 id="clubs-list">Клубы платформы</h2>
                </div>
                <div className="po-card po-empty">
                  <span className="po-icon">
                    <Building2 size={25} aria-hidden="true" />
                  </span>
                  <h3>Список клубов пока недоступен</h3>
                  <p>
                    После подключения данных здесь появятся клубы и этапы их
                    настройки.
                  </p>
                </div>
              </section>
              {preview && (
                <p className="po-local-note">
                  Предпросмотр интерфейса · без реальных данных
                </p>
              )}
            </>
          )}
        </main>
        {!inviting && (
          <nav className="po-nav" aria-label="Навигация платформы">
            {navigation.map(({ label, icon: Icon, available }) => (
              <button
                key={label}
                type="button"
                disabled={!available}
                aria-current={available ? 'page' : undefined}
                title={available ? label : `${label}: раздел ещё не подключён`}
              >
                <Icon size={21} aria-hidden="true" />
                <span>{label}</span>
                {!available && <small>Позже</small>}
              </button>
            ))}
          </nav>
        )}
      </div>
    </div>
  );
}
