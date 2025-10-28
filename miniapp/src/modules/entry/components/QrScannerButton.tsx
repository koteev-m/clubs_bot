import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useEntryStore } from '../state/entry.store';
import { http } from '../../../shared/api/http';
import { useUiStore } from '../../../shared/store/ui';

/** Button triggering Telegram QR scanner. */
export default function QrScannerButton() {
  const webApp = useTelegram();
  const { setResult } = useEntryStore();
  const { addToast } = useUiStore();

  function handleScan(text: string) {
    http
      .post('/api/checkin/qr', { code: text })
      .then(() => {
        setResult('ARRIVED');
        addToast('ARRIVED');
        webApp.closeScanQrPopup();
      })
      .catch(() => {
        setResult('DENIED');
        addToast('DENIED');
      });
  }

  function openScanner() {
    const listener = (data: string) => {
      handleScan(data);
      webApp.offEvent('qrTextReceived', listener as any);
    };
    webApp.onEvent('qrTextReceived', listener as any);
    webApp.showScanQrPopup();
  }

  return (
    <button onClick={openScanner} className="p-2 border">
      Сканировать QR
    </button>
  );
}
