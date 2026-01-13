import ClubPicker from '../../common/components/ClubPicker';
import NightPicker from '../../common/components/NightPicker';
import HallMap from './HallMap';
import { useGuestStore } from '../state/guest.store';
import ToastHost from '../../../widgets/ToastHost';
import BookingFlow from '../components/BookingFlow';
import SupportSection from '../components/SupportSection';

/** Main screen for Guest mode. */
export default function GuestHome() {
  const { selectedClub, selectedNight, selectedTable } = useGuestStore();

  return (
    <div className="p-4 space-y-4">
      <ClubPicker />
      <NightPicker />
      {selectedClub && selectedNight && <HallMap />}
      {selectedTable && <BookingFlow />}
      <SupportSection />
      <ToastHost />
    </div>
  );
}
