export class OnboardingError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
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
      throw new OnboardingError(response.status, code);
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
  if (error.code === 'version_conflict')
    return 'Запись уже изменена. Обновите данные перед повторным действием.';
  if (error.code === 'duplicate_application')
    return 'На это заведение уже есть действующая заявка.';
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
