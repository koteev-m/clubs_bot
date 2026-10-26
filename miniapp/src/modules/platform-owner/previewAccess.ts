export const PLATFORM_PREVIEW_PATH = '/__preview/platform';
export const PLATFORM_INVITE_PATH = `${PLATFORM_PREVIEW_PATH}/invite`;

interface EntryContext {
  development: boolean;
  hostname: string;
  pathname: string;
  search: string;
  baseUrl?: string;
}

/** A local UI preview is never evidence of a server-authorized platform owner. */
export function resolvePlatformOwnerEntry({
  development,
  hostname,
  pathname,
  search,
  baseUrl = '/',
}: EntryContext) {
  const base = baseUrl.replace(/\/$/, '');
  const route =
    base && pathname.startsWith(`${base}/`)
      ? pathname.slice(base.length)
      : pathname;
  const previewPath =
    route === PLATFORM_PREVIEW_PATH || route === PLATFORM_INVITE_PATH;
  const mode = new URLSearchParams(search).get('mode');
  const ownerMode =
    mode === 'owner' || mode === 'platform-owner' || mode === 'platform';
  if (
    previewPath &&
    development &&
    ['localhost', '127.0.0.1', '[::1]', '::1'].includes(hostname)
  ) {
    return 'preview';
  }
  if (route.startsWith(PLATFORM_PREVIEW_PATH)) return 'unavailable';
  if (mode === 'platform-owner') return 'protected';
  if (ownerMode) return 'unavailable';
  return null;
}
