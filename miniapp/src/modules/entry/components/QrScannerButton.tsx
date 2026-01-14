import { useCallback, useEffect, useRef } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useEntryStore } from '../state/entry.store';
import { checkinQr } from '../api/entry.api';
import { useUiStore } from '../../../shared/store/ui';
import { getApiErrorInfo } from '../../../shared/api/error';

/** Button triggering Telegram QR scanner. */
export default function QrScannerButton() {
  const webApp = useTelegram();
  const { setResult } = useEntryStore();
  const { addToast } = useUiStore();
  const listenersRef = useRef<{
    qrTextReceived?: ({ data }: { data: string }) => void;
    scanQrPopupClosed?: () => void;
  } | null>(null);
  const inFlightRef = useRef(false);

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

  const handleScan = useCallback(
    async (text: string) => {
      if (inFlightRef.current) return;
      inFlightRef.current = true;
      try {
        await checkinQr(text);
        setResult('ARRIVED');
        addToast('ARRIVED');
        webApp.closeScanQrPopup();
        cleanupListeners();
      } catch (error) {
        const { code, hasResponse } = getApiErrorInfo(error);
        if (!hasResponse) {
          addToast('Не удалось проверить QR (проблема с сетью). Сканируйте ещё раз.');
          return;
        }
        setResult('DENIED');
        if (code === 'invalid' || code === 'invalid_qr' || code === 'not_found') {
          addToast('QR недействителен');
          return;
        }
        if (code === 'forbidden') {
          addToast('Доступ запрещён');
          return;
        }
        addToast('Ошибка проверки QR');
      } finally {
        inFlightRef.current = false;
      }
    },
    [addToast, cleanupListeners, setResult, webApp],
  );

  function openScanner() {
    cleanupListeners();
    const handleQrText = ({ data }: { data: string }) => {
      handleScan(data);
    };
    const handleClosed = () => {
      inFlightRef.current = false;
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
