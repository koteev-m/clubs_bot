import ClubPicker from '../../common/components/ClubPicker';
import NightPicker from '../../common/components/NightPicker';
import HallMap from './HallMap';
import { useGuestStore } from '../state/guest.store';
import GuestsPicker from '../components/GuestsPicker';
import SplitPayPanel from '../../payments/components/SplitPayPanel';
import ToastHost from '../../../widgets/ToastHost';

/** Main screen for Guest mode. */
export default function GuestHome() {
  const { selectedClub, selectedNight, selectedTable } = useGuestStore();

  return (
    <div className="p-4 space-y-4">
      <ClubPicker />
      <NightPicker />
      {selectedClub && selectedNight && <HallMap />}
      {selectedTable && (
        <>
          <GuestsPicker />
          <SplitPayPanel />
        </>
      )}
      <ToastHost />
    </div>
  );
}
