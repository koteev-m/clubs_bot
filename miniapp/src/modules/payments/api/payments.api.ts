import { http } from '../../../shared/api/http';

/** Requests invoice creation on server. */
export function createInvoice(amount: number) {
  return http.post<{ url?: string; slug?: string }>('/api/confirm', { amount });
}
