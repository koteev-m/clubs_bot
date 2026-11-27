import { useRef, useState } from 'react';
import { useTelegram } from '../../app/providers/TelegramProvider';
import { Field } from './OnboardingShared';
import { businessRoleNames, isManagerRole, type Invitation } from './onboarding.types';
import { roleNames, type StaffRole } from './team.types';

export function invitationRole(invitation: Pick<Invitation, 'role'>) {
  const role = invitation.role;
  if (isManagerRole(role))
    return businessRoleNames[role];
  if (role && Object.hasOwn(roleNames, role))
    return roleNames[role as StaffRole];
  return 'Клиент платформы';
}

export function invitationScope(
  invitation: Pick<Invitation, 'clubs' | 'clubName'>,
) {
  return (
    invitation.clubs?.map((club) => club.name).join(', ') ||
    invitation.clubName ||
    'Заведения пока не назначены'
  );
}

async function copyInvitation(input: HTMLInputElement) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(input.value);
      return true;
    }
  } catch {
    // Some Telegram WebViews expose Clipboard but deny writes.
  }
  if (!input.isConnected) return false;
  input.focus({ preventScroll: true });
  input.select();
  input.setSelectionRange(0, input.value.length);
  try {
    return document.execCommand('copy') === true;
  } catch {
    return false;
  }
}

export default function InvitationDelivery({
  link,
  prefix,
  label = 'Ссылка приглашения',
  recipient,
  role,
  scope,
  expiresAt,
  hide,
  missingLinkHelp,
}: {
  link?: string;
  prefix: 'ci' | 'cm';
  label?: string;
  recipient: string;
  role: string;
  scope: string;
  expiresAt?: string;
  hide?: () => void;
  missingLinkHelp?: string;
}) {
  const webApp = useTelegram();
  const input = useRef<HTMLInputElement>(null);
  const [message, setMessage] = useState('');
  const start = link?.match(
    /^https:\/\/t\.me\/[A-Za-z][A-Za-z0-9_]{4,31}\?startapp=(ci|cm)_[A-Za-z0-9_-]{43}$/,
  );
  if (!link || start?.[1] !== prefix)
    return (
      <section className="po-card ob-card">
        <h2>Ссылка не получена</h2>
        <p>
          Первоначальный ответ со ссылкой не получен.{' '}
          {missingLinkHelp ?? 'Если запись сохранена, отзовите приглашение и создайте новое.'}
        </p>
      </section>
    );
  const shareSupported =
    typeof webApp.openTelegramLink === 'function' &&
    Boolean(webApp.initData) &&
    webApp.isVersionAtLeast?.('6.1');
  return (
    <section className="po-card ob-card" aria-label="Новое приглашение">
      <h2>Приглашение создано</h2>
      <p>Получатель: {recipient}</p>
      <p>Роль: {role}</p>
      <p>Заведения: {scope}</p>
      {expiresAt && (
        <p>Принять до {new Date(expiresAt).toLocaleString('ru-RU')}</p>
      )}
      <p>
        Отправьте приглашение сотруднику личным сообщением в Telegram. После
        принятия он получит доступ к своему кабинету.
      </p>
      <p className="po-muted">
        Ссылка одноразовая. Не передавайте её посторонним.
      </p>
      <Field label={label}>
        <input
          ref={input}
          readOnly
          value={link}
          onFocus={(event) => event.currentTarget.select()}
        />
      </Field>
      <button
        type="button"
        className="ob-button"
        onClick={async () => {
          if (!input.current) return;
          setMessage('');
          const copied = await copyInvitation(input.current);
          setMessage(
            copied
              ? 'Ссылка скопирована'
              : 'Не удалось скопировать. Выделите ссылку и скопируйте её вручную.',
          );
        }}
      >
        Скопировать ссылку
      </button>
      {shareSupported && (
        <button
          type="button"
          className="ob-button"
          onClick={() => {
            const params = new URLSearchParams({
              url: link,
              text: 'Приглашение в кабинет',
            });
            try {
              webApp.openTelegramLink('https://t.me/share/url?' + params);
              setMessage('');
            } catch {
              setMessage(
                'Не удалось открыть Telegram. Скопируйте ссылку для отправки.',
              );
            }
          }}
        >
          Отправить в Telegram
        </button>
      )}
      {hide && (
        <button type="button" className="ob-button" onClick={hide}>
          Скрыть ссылку
        </button>
      )}
      {message && <p role="status">{message}</p>}
    </section>
  );
}
