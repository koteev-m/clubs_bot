import { http } from '../../../shared/api/http';

/** Check-in guest via QR code. */
export function checkinQr(code: string, signal?: AbortSignal) {
  return http.post('/api/checkin/qr', { code }, { signal });
}

/** Manual check-in search. */
export function searchGuests(q: string) {
  return http.get(`/api/checkin/search?q=${encodeURIComponent(q)}`);
}

/** Add plus-one guest. */
export function plusOne() {
  return http.post('/api/checkin/plus-one');
}
