import { useCallback, useEffect, useRef } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useEntryStore } from '../state/entry.store';
import { hostScan } from '../api/entry.api';
import { useUiStore } from '../../../shared/store/ui';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';

interface QrScannerButtonProps {
  clubId: number;
  eventId: number;
}

/** Button triggering Telegram QR scanner. */
export default function QrScannerButton({ clubId, eventId }: QrScannerButtonProps) {
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
  const abortRef = useRef<AbortController | null>(null);

  const getDeniedToast = useCallback((reason?: string) => {
    switch (reason) {
      case 'ALREADY_USED':
        return 'QR уже использован';
      case 'TOKEN_INVALID':
        return 'QR недействителен';
      case 'TOKEN_REVOKED':
        return 'QR отозван';
      case 'TOKEN_EXPIRED':
        return 'Срок действия QR истёк';
      case 'SCOPE_MISMATCH':
        return 'QR не относится к этому событию';
      case 'INVALID_STATUS':
        return 'Вход недоступен по текущему статусу';
      case 'NOT_FOUND':
        return 'Запись не найдена';
      default:
        return 'DENIED';
    }
  }, []);

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

  const abortInFlight = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
  }, []);

  useEffect(
    () => () => {
      const wasOpen = isScannerOpenRef.current;
      isScannerOpenRef.current = false;
      scanSessionRef.current += 1;
      inFlightRef.current = false;
      abortInFlight();
      cleanupListeners();
      if (wasOpen) {
        webApp.closeScanQrPopup();
      }
    },
    [abortInFlight, cleanupListeners, webApp],
  );

  const handleScan = useCallback(
    async (text: string, sessionId: number) => {
      if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
      if (inFlightRef.current) return;
      inFlightRef.current = true;
      abortInFlight();
      const controller = new AbortController();
      abortRef.current = controller;
      try {
        const res = await hostScan({ clubId, eventId, qrPayload: text }, controller.signal);
        if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
        if (res.data.outcomeStatus === 'DENIED') {
          setResult('DENIED');
          addToast(getDeniedToast(res.data.denyReason));
          return;
        }
        setResult(res.data.outcomeStatus);
        addToast(res.data.outcomeStatus);
        isScannerOpenRef.current = false;
        scanSessionRef.current += 1;
        abortRef.current = null;
        webApp.closeScanQrPopup();
        cleanupListeners();
      } catch (error) {
        if (sessionId !== scanSessionRef.current || !isScannerOpenRef.current) return;
        if (isRequestCanceled(error)) return;
        const { code, hasResponse } = getApiErrorInfo(error);
        if (!hasResponse) {
          addToast('Не удалось проверить QR (проблема с сетью). Сканируйте ещё раз.');
          return;
        }
        setResult('DENIED');
        if (code === 'invalid' || code === 'invalid_qr' || code === 'not_found' || code === 'checkin_invalid_payload') {
          addToast('QR недействителен');
          return;
        }
        if (code === 'forbidden' || code === 'checkin_forbidden') {
          addToast('Доступ запрещён');
          return;
        }
        addToast('Ошибка проверки QR');
      } finally {
        if (abortRef.current === controller) {
          abortRef.current = null;
        }
        if (sessionId === scanSessionRef.current && isScannerOpenRef.current) {
          inFlightRef.current = false;
        }
      }
    },
    [abortInFlight, addToast, cleanupListeners, clubId, eventId, getDeniedToast, setResult, webApp],
  );

  function openScanner() {
    if (!clubId || !eventId) {
      addToast('Укажите клуб и событие');
      return;
    }
    cleanupListeners();
    abortInFlight();
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
      abortInFlight();
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
    <button type="button" onClick={openScanner} className="p-2 border w-full">
      Сканировать QR
    </button>
  );
}
