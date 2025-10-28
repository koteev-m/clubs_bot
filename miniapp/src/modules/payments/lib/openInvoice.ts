import WebApp from '@twa-dev/sdk';

export interface InvoiceLink {
  url?: string;
  slug?: string;
}

/** Opens Telegram invoice either by direct url or slug. */
export function openInvoice(link: InvoiceLink) {
  if (link.url) {
    WebApp.openInvoice(link.url);
    return 'url';
  }
  if (link.slug) {
    WebApp.openInvoice(link.slug);
    return 'slug';
  }
  throw new Error('Missing invoice link');
}
