type AdminForbiddenProps = {
  onExit: () => void;
};

export default function AdminForbidden({ onExit }: AdminForbiddenProps) {
  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] px-4 text-center">
      <h2 className="text-lg font-semibold text-gray-900">Нет доступа к админ-панели</h2>
      <p className="mt-2 text-sm text-gray-500">Проверьте свои права или обратитесь к администратору.</p>
      <button
        type="button"
        className="mt-4 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        onClick={onExit}
      >
        Вернуться
      </button>
    </div>
  );
}
