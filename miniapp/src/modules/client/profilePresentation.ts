// The location API supplies a display name, without language-tagged aliases.
// Preserve it (including localized names); never infer a translation from script.
export function locationPresentation(city: string, region?: string) {
  const name = city.trim();
  const area = region?.trim() ?? '';
  const normalize = (value: string) =>
    value.normalize('NFC').replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU');
  const distinctRegion =
    area && normalize(area) !== normalize(name) ? area : '';
  return {
    city: name,
    region: distinctRegion,
    label: [name, distinctRegion].filter(Boolean).join(', '),
  };
}

/** The saved venue zone, never the device zone; the offset is date-dependent. */
export function timezoneLabel(timezone: string, at = new Date()): string {
  try {
    const part = (timeZoneName: 'longGeneric' | 'longOffset') =>
      new Intl.DateTimeFormat('ru-RU', { timeZone: timezone, timeZoneName })
        .formatToParts(at)
        .find((item) => item.type === 'timeZoneName')?.value;
    const name = part('longGeneric');
    const offset = part('longOffset')?.replace(/^GMT/, 'UTC');
    return [name, offset].filter(Boolean).join(' · ') || timezone;
  } catch {
    // Older WebViews can have older IANA data. Keep the saved value intact.
    return timezone;
  }
}
