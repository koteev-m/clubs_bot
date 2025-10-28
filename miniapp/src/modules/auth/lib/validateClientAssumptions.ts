/** Validates minimal assumptions about Telegram client version. */
export function validateClientAssumptions(version: string) {
  if (!version) {
    throw new Error('Unsupported Telegram version');
  }
}
