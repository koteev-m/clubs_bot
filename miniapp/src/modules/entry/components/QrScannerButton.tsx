import { useCallback, useEffect, useRef } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useEntryStore } from '../state/entry.store';
import { http } from '../../../shared/api/http';
import { useUiStore } from '../../../shared/store/ui';

/** Button triggering Telegram QR scanner. */
export default function QrScannerButton() {
  const webApp = useTelegram();
  const { setResult } = useEntryStore();
  const { addToast } = useUiStore();
  const listenersRef = useRef<{
    qrTextReceived?: ({ data }: { data: string }) => void;
    scanQrPopupClosed?: () => void;
  } | null>(null);

  const cleanupListeners = useCallback(() => {
    const listeners = listenersRef.current;
    if (!listeners) return;
    if (listeners.qrTextReceived) {
      webApp.offEvent('qrTextReceived', listeners.qrTextReceived);
    }
    if (listeners.scanQrPopupClosed) {
      webApp.offEvent('scanQrPopupClosed', listeners.scanQrPopupClosed);
    }
    listenersRef.current = null;
  }, [webApp]);

  useEffect(() => () => cleanupListeners(), [cleanupListeners]);

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
    cleanupListeners();
    const handleQrText = ({ data }: { data: string }) => {
      handleScan(data);
      cleanupListeners();
    };
    const handleClosed = () => {
      cleanupListeners();
    };
    listenersRef.current = {
      qrTextReceived: handleQrText,
      scanQrPopupClosed: handleClosed,
    };
    webApp.onEvent('qrTextReceived', handleQrText);
    webApp.onEvent('scanQrPopupClosed', handleClosed);
    webApp.showScanQrPopup({});
  }

  return (
    <button onClick={openScanner} className="p-2 border">
      Сканировать QR
    </button>
  );
}
