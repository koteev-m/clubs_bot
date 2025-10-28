/** Masks E.164 phone number leaving last two digits. */
export function maskPhone(phone: string): string {
  const clean = phone.replace(/\D/g, '');
  if (clean.length < 2) return '*'.repeat(clean.length);
  const visible = clean.slice(-2);
  return `${'*'.repeat(clean.length - 2)}${visible}`;
}
