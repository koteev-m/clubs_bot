import { QRCodeCanvas } from 'qrcode.react';
import { useUiStore } from '../store/ui';

interface QrCodeBlockProps {
  payload: string;
  size?: number;
}

export default function QrCodeBlock({ payload, size = 200 }: QrCodeBlockProps) {
  const { addToast } = useUiStore();

  const copyPayload = async () => {
    if (!navigator.clipboard?.writeText) {
      addToast('Не удалось скопировать');
      return;
    }
    try {
      await navigator.clipboard.writeText(payload);
      addToast('Код скопирован');
    } catch {
      addToast('Не удалось скопировать');
    }
  };

  return (
    <div className="rounded border bg-gray-50 p-3 space-y-3">
      <div className="flex justify-center">
        <QRCodeCanvas value={payload} size={size} includeMargin />
      </div>
      <button
        className="w-full rounded bg-gray-200 px-3 py-2 text-sm text-gray-800 disabled:opacity-60"
        type="button"
        onClick={() => void copyPayload()}
      >
        Скопировать код
      </button>
    </div>
  );
}
