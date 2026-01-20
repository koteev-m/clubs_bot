import { useCallback, useEffect, useMemo, useState } from 'react';
import { QRCodeCanvas } from 'qrcode.react';
import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useUiStore } from '../../../shared/store/ui';
import {
  listGuestLists,
  listInvitations,
  normalizePromoterError,
  PromoterGuestList,
  PromoterInvitationEntry,
} from '../api/promoter.api';

type InvitationsScreenProps = {
  guestListId: number | null;
  onSelectGuestList: (id: number | null) => void;
  onForbidden: () => void;
};

const parseOptionalId = (value: string) => {
  const id = Number(value);
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

export default function InvitationsScreen({ guestListId, onSelectGuestList, onForbidden }: InvitationsScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const webApp = useTelegram();
  const [guestLists, setGuestLists] = useState<PromoterGuestList[]>([]);
  const [entries, setEntries] = useState<PromoterInvitationEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [openQr, setOpenQr] = useState<number | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const load = async () => {
      try {
        const lists = await listGuestLists(undefined, controller.signal);
        setGuestLists(lists);
        if (!guestListId && lists.length > 0) {
          onSelectGuestList(lists[0].id);
        }
      } catch (error) {
        const normalized = normalizePromoterError(error);
        if (normalized.status === 401 || normalized.status === 403) {
          onForbidden();
        } else {
          addToast(normalized.message);
        }
      }
    };
    load();
    return () => controller.abort();
  }, [addToast, guestListId, onForbidden, onSelectGuestList]);

  useEffect(() => {
    if (!guestListId) {
      setEntries([]);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    const load = async () => {
      try {
        setLoading(true);
        const list = await listInvitations(guestListId, controller.signal);
        setEntries(list);
      } catch (error) {
        const normalized = normalizePromoterError(error);
        if (normalized.status === 401 || normalized.status === 403) {
          onForbidden();
        } else {
          addToast(normalized.message);
        }
      } finally {
        setLoading(false);
      }
    };
    load();
    return () => controller.abort();
  }, [addToast, guestListId, onForbidden]);

  const handleCopy = useCallback(
    async (url: string) => {
      try {
        await navigator.clipboard.writeText(url);
        addToast('Ссылка скопирована');
      } catch {
        addToast('Не удалось скопировать ссылку');
      }
    },
    [addToast],
  );

  const handleShare = useCallback(
    (url: string) => {
      const shareUrl = `https://t.me/share/url?url=${encodeURIComponent(url)}`;
      if ('openTelegramLink' in webApp && typeof webApp.openTelegramLink === 'function') {
        webApp.openTelegramLink(shareUrl);
        return;
      }
      if ('openLink' in webApp && typeof webApp.openLink === 'function') {
        webApp.openLink(shareUrl);
        return;
      }
      window.open(shareUrl, '_blank', 'noopener,noreferrer');
    },
    [webApp],
  );

  const activeListLabel = useMemo(() => {
    const list = guestLists.find((item) => item.id === guestListId);
    return list ? `${list.name} (#${list.id})` : 'Выберите список';
  }, [guestListId, guestLists]);

  return (
    <div className="px-4 py-6">
      <h2 className="text-lg font-semibold text-gray-900">Приглашения</h2>
      <div className="mt-4 rounded-lg bg-white p-4 shadow-sm">
        <label className="text-xs text-gray-500">
          Гостевой список
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={guestListId ?? ''}
              onChange={(event) => onSelectGuestList(parseOptionalId(event.target.value))}
            >
            <option value="">{activeListLabel}</option>
            {guestLists.map((list) => (
              <option key={list.id} value={list.id}>
                {list.name} (#{list.id})
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="mt-4 space-y-3">
        {loading ? (
          <div className="text-sm text-gray-500">Загрузка...</div>
        ) : entries.length === 0 ? (
          <div className="text-sm text-gray-500">Нет приглашений для списка.</div>
        ) : (
          entries.map((item) => (
            <div key={item.entry.id} className="rounded-lg bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-semibold text-gray-900">{item.entry.displayName}</div>
                  <div className="text-xs text-gray-500">Статус: {item.entry.status}</div>
                </div>
                <button
                  type="button"
                  className="text-xs text-blue-600"
                  onClick={() => setOpenQr(openQr === item.entry.id ? null : item.entry.id)}
                >
                  QR
                </button>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  className="rounded-md bg-blue-600 px-3 py-2 text-xs font-semibold text-white"
                  onClick={() => handleShare(item.invitationUrl)}
                >
                  Отправить в Telegram
                </button>
                <button
                  type="button"
                  className="rounded-md border border-gray-200 px-3 py-2 text-xs font-semibold text-gray-700"
                  onClick={() => handleCopy(item.invitationUrl)}
                >
                  Копировать ссылку
                </button>
              </div>
              {openQr === item.entry.id ? (
                <div className="mt-3 rounded-md border border-dashed border-gray-200 p-3 text-center">
                  <QRCodeCanvas value={item.invitationUrl} size={140} />
                  <div className="mt-2 text-xs text-gray-500">Срок до: {new Date(item.expiresAt).toLocaleString()}</div>
                </div>
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
