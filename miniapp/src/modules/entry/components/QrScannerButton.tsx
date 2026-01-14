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
  const scanSessionRef = useRef(0);
  const isScannerOpenRef = useRef(false);
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
    async (text: string, sessionId: number) => {
      if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
      if (inFlightRef.current) return;
      inFlightRef.current = true;
      try {
        await checkinQr(text);
        if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
        setResult('ARRIVED');
        addToast('ARRIVED');
        isScannerOpenRef.current = false;
        scanSessionRef.current += 1;
        webApp.closeScanQrPopup();
        cleanupListeners();
      } catch (error) {
        if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
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
        if (sessionId === scanSessionRef.current && isScannerOpenRef.current) {
          inFlightRef.current = false;
        }
      }
    },
    [addToast, cleanupListeners, setResult, webApp],
  );

  function openScanner() {
    cleanupListeners();
    scanSessionRef.current += 1;
    const sessionId = scanSessionRef.current;
    isScannerOpenRef.current = true;
    inFlightRef.current = false;
    const handleQrText = ({ data }: { data: string }) => {
      void handleScan(data, sessionId);
    };
    const handleClosed = () => {
      if (sessionId !== scanSessionRef.current) return;
      isScannerOpenRef.current = false;
      scanSessionRef.current += 1;
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
    <button type="button" onClick={openScanner} className="p-2 border">
      Сканировать QR
    </button>
  );
}
