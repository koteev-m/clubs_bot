const invitationParameter = /^ci_([A-Za-z0-9_-]{43})$/;

/** Untrusted launch data chooses a screen only; the server checks the token and signed identity. */
export function readClientEntry(
  search: string,
  hash: string,
  sdkStartParam?: string,
) {
  const query = new URLSearchParams(search);
  const fragment = new URLSearchParams(hash.replace(/^#/, ''));
  const embedded = new URLSearchParams(fragment.get('tgWebAppData') ?? '');
  const candidates = [
    sdkStartParam,
    query.get('startapp'),
    query.get('tgWebAppStartParam'),
    fragment.get('tgWebAppStartParam'),
    embedded.get('start_param'),
  ].filter(Boolean);
  const invitation = candidates.find(
    (value) => value && invitationParameter.test(value),
  );
  return {
    client:
      query.get('mode') === 'client' ||
      candidates.includes('client') ||
      Boolean(invitation),
    token: invitation?.match(invitationParameter)?.[1] ?? null,
  };
}

/** Remove copied launch credentials from our address/history without modifying Telegram's SDK data. */
export function scrubClientLaunchUrl() {
  const url = new URL(window.location.href);
  for (const key of ['startapp', 'tgWebAppStartParam', 'initData', 'init_data'])
    url.searchParams.delete(key);
  const fragment = new URLSearchParams(url.hash.slice(1));
  for (const key of ['tgWebAppData', 'tgWebAppStartParam'])
    fragment.delete(key);
  url.hash = fragment.toString();
  url.searchParams.set('mode', 'client');
  window.history.replaceState(window.history.state, '', url);
}
