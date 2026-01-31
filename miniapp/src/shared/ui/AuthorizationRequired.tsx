export default function AuthorizationRequired() {
  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      <div className="max-w-sm w-full rounded-lg border border-gray-200 bg-white p-5 text-center space-y-2">
        <div className="text-lg font-semibold">Нужна авторизация</div>
        <div className="text-sm text-gray-600">
          Перезапустите мини‑приложение в Telegram и подтвердите доступ.
        </div>
      </div>
    </div>
  );
}
