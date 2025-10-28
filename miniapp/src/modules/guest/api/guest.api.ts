import { http } from '../../../shared/api/http';
import { NightDto, TableAvailabilityDto } from '../../../shared/types';

/** Lists nights for club. */
export function listOpenNights(clubId: number) {
  return http.get<NightDto[]>(`/api/clubs/${clubId}/nights?limit=8`);
}

/** Lists free tables for night. */
export function listFreeTables(clubId: number, startUtc: string) {
  return http.get<TableAvailabilityDto[]>(`/api/clubs/${clubId}/nights/${startUtc}/tables/free`);
}
