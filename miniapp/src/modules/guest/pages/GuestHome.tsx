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
      <ClubPicker />
      <NightPicker />
      {hasClub && hasNight && <HallMap />}
      {hasClub && hasNight && <BookingFlow />}
      {!hasTable && hasClub && hasNight && (
        <div className="text-sm text-gray-500">Выберите стол, чтобы продолжить.</div>
      )}
      <SupportSection />
    </div>
  );
}
