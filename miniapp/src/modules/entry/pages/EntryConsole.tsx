import QrScannerButton from '../components/QrScannerButton';
import ManualSearch from '../components/ManualSearch';
import PlusOneControl from '../components/PlusOneControl';
import ToastHost from '../../../widgets/ToastHost';
import { useEntryStore } from '../state/entry.store';

/** Console for entry managers. */
export default function EntryConsole() {
  const { lastResult } = useEntryStore();
  return (
    <div className="p-4 space-y-4">
      <QrScannerButton />
      <ManualSearch />
      <PlusOneControl />
      {lastResult && <div>Last: {lastResult}</div>}
      <ToastHost />
    </div>
  );
}
