import WebApp from '@twa-dev/sdk';
import { openInvoice } from './openInvoice';

vi.mock('@twa-dev/sdk', () => ({
  default: {
    openInvoice: vi.fn(),
  },
}));

describe('openInvoice', () => {
  it('opens by url', () => {
    openInvoice({ url: 'https://pay' });
    expect(WebApp.openInvoice).toHaveBeenCalledWith('https://pay');
  });
  it('opens by slug', () => {
    openInvoice({ slug: 'abc' });
    expect(WebApp.openInvoice).toHaveBeenCalledWith('abc');
  });
});
