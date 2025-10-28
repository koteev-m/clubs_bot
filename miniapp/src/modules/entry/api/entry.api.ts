import { http } from '../../../shared/api/http';

/** Check-in guest via QR code. */
export function checkinQr(code: string) {
  return http.post('/api/checkin/qr', { code });
}

/** Manual check-in search. */
export function searchGuests(q: string) {
  return http.get(`/api/checkin/search?q=${encodeURIComponent(q)}`);
}

/** Add plus-one guest. */
export function plusOne() {
  return http.post('/api/checkin/plus-one');
}
