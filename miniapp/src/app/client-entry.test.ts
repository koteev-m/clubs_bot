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
  it.each(['ci', 'cm'])(
    'preserves %s invitation priority and fragment transport',
    (prefix) => {
      const hash =
        '#tgWebAppStartParam=' +
        prefix +
        '_' +
        'a'.repeat(43) +
        '&tgWebAppData=signed';
      expect(launch('', hash).destination).toBe('/app?mode=client' + hash);
    },
  );
  it.each(['ci_', 'cm_'])(
    'moves a query-only %s invitation into the fragment with its exact outer prefix',
    (prefix) => {
      const token = 'cm_' + 'F'.repeat(40);
      const start = prefix + token;
      const result = launch('?startapp=' + start, '#tgWebAppData=signed');
      const redirect = new URL(result.destination!, 'https://example.test');
      expect(redirect.search).toBe('?mode=client');
      expect(
        new URLSearchParams(redirect.hash.slice(1)).get('tgWebAppStartParam'),
      ).toBe(start);
      expect(
        new URLSearchParams(redirect.hash.slice(1)).get('tgWebAppData'),
      ).toBe('signed');
    },
  );
});
