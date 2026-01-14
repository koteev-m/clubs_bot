export function tgInitData() {
  try {
    return (window?.Telegram?.WebApp?.initData) || "";
  } catch {
    return "";
  }
}

export function todayUtc() {
  const now = new Date();
  const utc = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  return utc.toISOString().slice(0, 10);
}
