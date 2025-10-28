export interface ClubDto {
  id: number;
  name: string;
}

export interface NightDto {
  startUtc: string;
  name: string;
}

export interface TableAvailabilityDto {
  id: number;
  number: string;
  capacity: number;
  status: 'FREE' | 'HELD' | 'BOOKED';
}
