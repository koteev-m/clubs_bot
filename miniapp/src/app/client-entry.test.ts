import source from '../main/resources/miniapp/assets/js/client-entry.js?raw';
import { describe, expect, it } from 'vitest';

function launch(search: string, hash: string) {
  let destination: string | undefined;
  const document = { documentElement: { hidden: false } };
  const window = {
    location: {
      search,
      hash,
      replace: (url: string) => {
        destination = url;
      },
    },
    __clientEntryRedirect: false,
  };
  // Execute the exact packaged navigation script against a synthetic browser surface.
  new Function('window', 'document', source)(window, document);
  return { destination, hidden: document.documentElement.hidden };
}

describe('packaged entry script', () => {
  it('routes signed bare Main Mini App into cabinet without copying credentials to query', () => {
    const hash = '#tgWebAppData=user%3Dtest%26hash%3Dsignature';
    expect(launch('', hash)).toEqual({
      destination: '/app?mode=cabinet' + hash,
      hidden: true,
    });
  });
  it('keeps explicit guest entry and ordinary browser entry intact', () => {
    expect(
      launch('?mode=guest', '#tgWebAppData=signed').destination,
    ).toBeUndefined();
    expect(launch('', '').destination).toBeUndefined();
  });
  it('preserves direct invitation priority and fragment transport', () => {
    const hash =
      '#tgWebAppStartParam=ci_' + 'a'.repeat(43) + '&tgWebAppData=signed';
    expect(launch('', hash).destination).toBe('/app?mode=client' + hash);
  });
});
