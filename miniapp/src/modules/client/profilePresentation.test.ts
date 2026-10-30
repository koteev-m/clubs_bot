import { describe, expect, it } from 'vitest';
import { locationPresentation, timezoneLabel } from './profilePresentation';

describe('location presentation from authoritative display names', () => {
  it.each([
    ['Moscow', 'Moscow', 'Moscow'],
    ['Москва', 'Москва', 'Москва'],
    ['  New  York ', 'new york', 'New  York'],
    ['Paris', 'Île-de-France', 'Paris, Île-de-France'],
    ['Paris', 'Texas', 'Paris, Texas'],
    ['Москва', undefined, 'Москва'],
    ['Moscow', '', 'Moscow'],
    // Similar spelling is not proof that the region duplicates the city.
    ['Москва', 'Московская область', 'Москва, Московская область'],
    ['Moscow', 'Москва', 'Moscow, Москва'],
  ])(
    '%s / %s → %s without translating or changing identity',
    (city, region, expected) => {
      expect(locationPresentation(city, region).label).toBe(expected);
    },
  );
});

describe('venue timezone presentation', () => {
  it('uses the stored zone for the offset, including fractional hours', () => {
    expect(
      timezoneLabel('Asia/Kathmandu', new Date('2026-01-15T12:00:00Z')),
    ).toContain('UTC+05:45');
    expect(
      timezoneLabel('Europe/Moscow', new Date('2026-01-15T12:00:00Z')),
    ).toContain('UTC+03:00');
  });
  it('does not imply a permanent offset for a zone with daylight saving time', () => {
    expect(
      timezoneLabel('America/New_York', new Date('2026-01-15T12:00:00Z')),
    ).toContain('UTC-05:00');
    expect(
      timezoneLabel('America/New_York', new Date('2026-07-15T12:00:00Z')),
    ).toContain('UTC-04:00');
  });
  it('falls back to the original value if the browser does not recognize a saved zone', () => {
    expect(timezoneLabel('Future/Server_Zone')).toBe('Future/Server_Zone');
  });
});
