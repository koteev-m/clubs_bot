export class OnboardingError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly businessDate?: string,
  ) {
    super('Onboarding request failed');
  }
}

/** Credentials and mutation keys are kept in memory; each request binds its own identity. */
export async function onboardingRequest<T>(
  credential: string,
  path: string,
  options: {
    method?: string;
    body?: unknown;
    signal?: AbortSignal;
    key?: string;
  } = {},
): Promise<T> {
  if (!credential) throw new OnboardingError(401, 'unauthorized');
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener('abort', abort, { once: true });
  if (options.signal?.aborted) controller.abort();
  const timeout = window.setTimeout(abort, 15000);
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
      cache: 'no-store',
      credentials: 'same-origin',
      referrerPolicy: 'no-referrer',
      headers: {
        'X-Telegram-Init-Data': credential,
        ...(options.body !== undefined
          ? { 'Content-Type': 'application/json' }
          : {}),
        ...(options.key ? { 'Idempotency-Key': options.key } : {}),
      },
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
    if (!response.ok) {
      const data: unknown = await response.json().catch(() => null);
      const code =
        data &&
        typeof data === 'object' &&
        'code' in data &&
        typeof data.code === 'string'
          ? data.code
          : 'request_failed';
      if (
        !controller.signal.aborted &&
        ([401, 403].includes(response.status) ||
          (response.status === 404 &&
            /\/clients\/[1-9][0-9]*\/onboarding$/.test(path))) &&
        path.startsWith('/api/client/')
      )
        window.dispatchEvent(
          new CustomEvent('client-authority-lost', { detail: credential }),
        );
      const details =
        data && typeof data === 'object' && 'details' in data
          ? data.details
          : null;
      const date =
        details && typeof details === 'object' && 'businessDate' in details
          ? details.businessDate
          : null;
      throw new OnboardingError(
        response.status,
        code,
        typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)
          ? date
          : undefined,
      );
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof OnboardingError) throw error;
    throw new OnboardingError(0, 'unknown_outcome');
  } finally {
    window.clearTimeout(timeout);
    options.signal?.removeEventListener('abort', abort);
  }
}

export function errorMessage(error: unknown): string {
  if (!(error instanceof OnboardingError))
    return 'Не удалось загрузить данные. Повторите попытку.';
  if (error.status === 401)
    return 'Нужна свежая авторизация. Откройте мини-приложение заново из Telegram.';
  if (error.status === 403 || error.status === 404)
    return 'Доступ утрачен или запись недоступна. Вернитесь в кабинет и обновите данные.';
  if (error.code === 'club_limit_unset')
    return 'Лимит заведений пока не настроен. Обратитесь к владельцу платформы. Существующие заведения доступны.';
  if (error.code === 'club_limit_reached')
    return 'Лимит заведений исчерпан. Обратитесь к владельцу платформы для увеличения.';
  if (error.code === 'club_limit_below_usage')
    return 'Лимит не может быть меньше количества уже созданных заведений.';
  if (error.code === 'location_required' || error.code === 'location_not_found')
    return 'Выберите город и регион из списка. Если город не найден, уточните страну или название.';
  const date = error.businessDate
    ? ' (' + error.businessDate.split('-').reverse().join('.') + ')'
    : '';
  if (error.code === 'calendar_night_dependencies')
    return (
      'Ночь' +
      date +
      ' связана с событием, бронями, удержаниями или операционными записями. Календарь не сохранён.'
    );
  if (error.code === 'calendar_night_started')
    return (
      'Ночь' +
      date +
      ' уже началась или новые часы относятся к прошлому. Календарь не сохранён.'
    );
  if (error.code === 'calendar_night_overlap')
    return (
      'Ночь' +
      date +
      ' пересекается с другой клубной ночью. Проверьте даты и часы.'
    );
  if (error.code === 'calendar_night_invalid')
    return (
      'Для ночи' + date + ' нельзя рассчитать интервал с учётом перевода часов.'
    );
  if (error.code === 'calendar_night_cutover_required')
    return 'Согласование ночей пока доступно только для неопубликованных клубов.';
  if (error.code === 'version_conflict')
    return 'Запись уже изменена. Обновите данные перед повторным действием.';
  if (error.code === 'client_not_empty')
    return 'Архивирование недоступно: у организации есть заведения, активные права или действующие приглашения. Обновите данные.';
  if (error.code === 'client_archived')
    return 'Организация находится в архиве. Доступ и принятие приглашений недоступны.';
  if (error.code === 'duplicate_application')
    return 'Уже есть сохранённое заведение или заявка с такими названием и адресом. Откройте сохранённую запись и уточните местоположение.';
  if (error.code === 'invalid_transition')
    return 'Действие больше недоступно в текущем состоянии. Обновите данные.';
  if (error.code === 'idempotency_conflict')
    return 'Команда уже использована с другими данными. Обновите состояние.';
  if (error.code === 'invitation_link_unavailable')
    return 'Telegram-ссылка недоступна: требуется настройка платформенного бота. Приглашение не создано. Сообщите администратору платформы.';
  if (error.status === 400)
    return 'Проверьте заполненные поля и повторите действие.';
  return 'Не удалось загрузить данные. Повторите попытку.';
}

export function authorityLost(error: unknown): boolean {
  return (
    error instanceof OnboardingError && [401, 403, 404].includes(error.status)
  );
}
