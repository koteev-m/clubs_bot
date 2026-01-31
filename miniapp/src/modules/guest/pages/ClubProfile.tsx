import { useGuestStore } from '../state/guest.store';
import { useClubGamification } from '../hooks/useClubGamification';
import AuthorizationRequired from '../../../shared/ui/AuthorizationRequired';
import ProgressCard from '../../../shared/ui/ProgressCard';
import BadgeList from '../../../shared/ui/BadgeList';

export default function ClubProfile() {
  const { selectedClub } = useGuestStore();
  const { status, data, errorMessage, canRetry, reload } = useClubGamification(selectedClub);

  if (status === 'unauthorized') {
    return <AuthorizationRequired />;
  }

  return (
    <div className="p-4 space-y-4 bg-gray-50 min-h-screen">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Профиль клуба</h2>
        <button
          className="text-sm underline disabled:text-gray-400"
          type="button"
          onClick={() => void reload()}
          disabled={!selectedClub || status === 'loading'}
        >
          Обновить
        </button>
      </div>
      {!selectedClub && (
        <div className="rounded-lg border border-dashed border-gray-200 bg-white p-4 text-sm text-gray-500">
          Выберите клуб, чтобы увидеть прогресс и бейджи.
        </div>
      )}
      {selectedClub && status === 'loading' && <div className="text-sm text-gray-500">Загрузка...</div>}
      {selectedClub && status === 'error' && (
        <div className="rounded-lg border border-red-100 bg-red-50 p-4 text-sm text-red-700 space-y-3">
          <div>{errorMessage}</div>
          {canRetry && (
            <button
              className="rounded bg-red-600 px-3 py-2 text-sm text-white"
              type="button"
              onClick={() => void reload()}
            >
              Повторить
            </button>
          )}
        </div>
      )}
      {selectedClub && status === 'ready' && data && (
        <>
          <ProgressCard rewards={data.nextRewards} />
          <section className="space-y-2">
            <div className="text-base font-semibold">Бейджи</div>
            <BadgeList badges={data.badges} />
          </section>
        </>
      )}
    </div>
  );
}
