import ClubPicker from '../../common/components/ClubPicker';
import NightPicker from '../../common/components/NightPicker';
import HallMap from './HallMap';
import { useGuestStore } from '../state/guest.store';
import BookingFlow from '../components/BookingFlow';
import SupportSection from '../components/SupportSection';

/** Main screen for Guest mode. */
export default function GuestHome() {
  const { selectedClub, selectedNight, selectedTable } = useGuestStore();
  const hasClub = Boolean(selectedClub);
  const hasNight = Boolean(selectedNight);
  const hasTable = Boolean(selectedTable);

  return (
    <div className="p-4 space-y-4">
      <section className="rounded border p-4 space-y-2">
        <div className="text-base font-semibold">Шаги бронирования</div>
        <ol className="space-y-1 text-sm">
          <FlowStep index={1} label="Клуб" active={!hasClub} done={hasClub} />
          <FlowStep index={2} label="Ночь/событие" active={hasClub && !hasNight} done={hasNight} />
          <FlowStep index={3} label="Схема зала" active={hasNight && !hasTable} done={hasTable} />
          <FlowStep index={4} label="Оформление (гости → правила → подтверждение)" active={hasTable} done={false} />
        </ol>
      </section>

      <section className="space-y-2">
        <div className="text-base font-semibold">Шаг 1. Выбор клуба</div>
        <ClubPicker />
      </section>

      <section className="space-y-2">
        <div className="text-base font-semibold">Шаг 2. Выбор ночи</div>
        {hasClub ? <NightPicker /> : <div className="text-sm text-gray-500">Сначала выберите клуб.</div>}
      </section>

      <section className="space-y-2">
        <div className="text-base font-semibold">Шаг 3. Схема зала</div>
        {hasClub && hasNight ? (
          hasTable ? (
            <div className="text-sm text-gray-600">Стол выбран. Можно перейти к оформлению.</div>
          ) : (
            <HallMap />
          )
        ) : (
          <div className="text-sm text-gray-500">Выберите клуб и ночь, чтобы увидеть схему.</div>
        )}
      </section>

      <section className="space-y-2">
        <div className="text-base font-semibold">Шаг 4. Оформление брони</div>
        {hasTable ? <BookingFlow /> : <div className="text-sm text-gray-500">Выберите стол, чтобы продолжить.</div>}
      </section>

      <SupportSection />
    </div>
  );
}

function FlowStep({ index, label, active, done }: { index: number; label: string; active?: boolean; done?: boolean }) {
  return (
    <li className="flex items-center gap-2">
      <span
        className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-semibold ${
          done ? 'bg-green-100 text-green-700' : active ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-500'
        }`}
      >
        {index}
      </span>
      <span className={done ? 'text-gray-900' : 'text-gray-600'}>{label}</span>
    </li>
  );
}
