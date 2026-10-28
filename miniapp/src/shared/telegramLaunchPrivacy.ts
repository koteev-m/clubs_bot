/**
 * The bundled Telegram SDK optionally caches launch parameters before exposing WebApp.
 * Keep signed credentials in SDK memory only, including invitation start_param.
 * This does not control storage inside Telegram's own infrastructure.
 */
const launchStorageKey = '__telegram__initParams';
export function withoutLaunchCredentials(value: string): string {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
      return '{}';
    const clean = { ...parsed } as Record<string, unknown>;
    delete clean.tgWebAppData;
    delete clean.tgWebAppStartParam;
    return JSON.stringify(clean);
  } catch {
    return '{}';
  }
}

if (typeof window !== 'undefined') {
  try {
    const storage = window.sessionStorage;
    const nativeSetItem = Storage.prototype.setItem;
    const stored = storage.getItem(launchStorageKey);
    if (stored !== null)
      nativeSetItem.call(
        storage,
        launchStorageKey,
        withoutLaunchCredentials(stored),
      );
    Storage.prototype.setItem = function (key: string, value: string) {
      const safeValue =
        this === storage && key === launchStorageKey
          ? withoutLaunchCredentials(String(value))
          : value;
      nativeSetItem.call(this, key, safeValue);
    };
  } catch {
    // Unavailable browser storage is fine: credentials remain in SDK memory.
  }
}
