import { expect, it } from 'vitest';
import { arrivalOptions } from './ClubNightDeskBookings';

const night = (opensAt: string, closesAt: string) => ({
  id: '17', businessDate: '2026-11-27', opensAt, closesAt,
});
const utc = (value: string[]) => value.map((time) => new Date(time).toISOString());

it('resolves midnight and tomorrow opening against the actual night interval', () => {
  const afterMidnight = night('2026-11-27T21:00Z', '2026-11-28T04:00Z');
  expect(utc(arrivalOptions(afterMidnight, '00:30', 'Europe/Moscow'))).toEqual(['2026-11-27T21:30:00.000Z']);
  expect(arrivalOptions(afterMidnight, '23:30', 'Europe/Moscow')).toEqual([]);
  expect(arrivalOptions(afterMidnight, '07:00', 'Europe/Moscow')).toEqual([]);
  const crosses = night('2026-11-27T18:00Z', '2026-11-28T02:00Z');
  expect(utc(arrivalOptions(crosses, '00:30', 'Europe/Moscow'))).toEqual(['2026-11-27T21:30:00.000Z']);
});

it('lists both DST occurrences and rejects a clock gap', () => {
  const repeats = night('2026-11-01T04:00Z', '2026-11-01T08:00Z');
  expect(new Set(utc(arrivalOptions(repeats, '01:30', 'America/New_York'))))
    .toEqual(new Set(['2026-11-01T05:30:00.000Z', '2026-11-01T06:30:00.000Z']));
  const gap = night('2026-03-08T05:00Z', '2026-03-08T10:00Z');
  expect(arrivalOptions(gap, '02:30', 'America/New_York')).toEqual([]);
});
