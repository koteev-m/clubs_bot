import { http } from '../../../shared/api/http';

export interface MyBookingDto {
  booking: {
    id: number;
    clubId: number;
    eventId: number;
    status: string;
    guestCount: number;
    arrivalWindow: string[];
    latePlusOneAllowedUntil?: string | null;
    plusOneUsed: boolean;
  };
  arrivalWindow: string[];
  latePlusOneAllowedUntil?: string | null;
  canPlusOne: boolean;
  isPast: boolean;
  arriveBy: string;
}

export interface MyBookingsResponse {
  bookings: MyBookingDto[];
}

export function fetchMyBookings(status: 'upcoming' | 'past') {
  return http.get<MyBookingsResponse>(`/api/me/bookings?status=${status}`);
}

export function fetchBookingQr(bookingId: number) {
  return http.get<{ qrPayload: string; bookingId: number }>(`/api/bookings/${bookingId}/qr`);
}

export function downloadBookingIcs(bookingId: number) {
  return http.get<Blob>(`/api/bookings/${bookingId}/ics`, { responseType: 'blob' });
}
