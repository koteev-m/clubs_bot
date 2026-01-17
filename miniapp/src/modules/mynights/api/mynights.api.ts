import { http } from '../../../shared/api/http';
import type { AxiosRequestConfig } from 'axios';

export interface MyBookingDto {
  booking: {
    id: number;
    clubId: number;
    tableId?: number;
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

export function fetchMyBookings(status: 'upcoming' | 'past', config?: AxiosRequestConfig) {
  return http.get<MyBookingsResponse>(`/api/me/bookings?status=${status}`, config);
}

export function fetchBookingQr(bookingId: number) {
  return http.get<{ qrPayload: string; bookingId: number }>(`/api/bookings/${bookingId}/qr`);
}

export function downloadBookingIcs(bookingId: number) {
  return http.get<Blob>(`/api/bookings/${bookingId}/ics`, { responseType: 'blob' });
}

export function requestBookingPlusOne(bookingId: number) {
  return http.post(`/api/bookings/${bookingId}/plus-one`);
}
