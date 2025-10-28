(function () {
  const tg = window.Telegram && window.Telegram.WebApp;
  const byId = (id) => document.getElementById(id);
  const scanBtn = byId('scanBtn');
  const clubIdInput = byId('clubId');
  const statusEl = byId('status');

  // --- utils ---
  const q = new URLSearchParams(location.search);
  const clubId = q.get('clubId'); // ожидаем, что Mini App открывается как /webapp/entry?clubId=123
  if (clubId) clubIdInput.value = clubId;

  const setStatus = (html, ok = true) => {
    statusEl.className = ok ? 'toast-ok' : 'toast-err';
    statusEl.innerHTML = html;
  };

  const toast = (message, ok = true) => {
    try {
      tg.showPopup({ title: ok ? 'Готово' : 'Ошибка', message, buttons: [{ type: 'close' }] });
    } catch (_) { /* ignore */ }
    setStatus(message, ok);
  };

  const haptic = (type) => {
    try { tg.HapticFeedback.impactOccurred(type || 'light'); } catch (_) {}
  };

  const assertWebApp = () => {
    if (!tg) {
      setStatus('Запустите через Telegram Mini App', false);
      scanBtn.disabled = true;
      throw new Error('No Telegram.WebApp');
    }
  };

  const ready = () => {
    try { tg.ready(); tg.expand(); } catch (_) {}
    // Тема
    try {
      const theme = tg.themeParams || {};
      // можно тонко адаптировать цвета под тему; опускаем для краткости
    } catch (_) {}
  };

  // --- QR flow ---
  let scanning = false;

  const closeScanner = () => {
    try { tg.closeScanQrPopup(); } catch (_) {}
  };

  const openScanner = async () => {
    assertWebApp();
    if (scanning) return;
    scanning = true;
    setStatus('Откройте камеру и наведите на QR');
    haptic('light');
    try {
      tg.showScanQrPopup({ text: 'Сканируйте QR гостя' });
    } catch (e) {
      scanning = false;
      setStatus('Ваш Telegram не поддерживает сканер QR', false);
    }
  };

  const postScan = async (qr) => {
    assertWebApp();
    const initData = tg.initData || '';
    if (!clubId) {
      toast('Не указан clubId в URL', false);
      return;
    }
    // POST в наш чек-ин (сервер позже будет валидировать initData в P02-02)
    const url = `/api/clubs/${encodeURIComponent(clubId)}/checkin/scan`;
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 10000);
      const resp = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-Init-Data': initData
        },
        body: JSON.stringify({ qr }),
        signal: ctrl.signal
      });
      clearTimeout(t);

      if (resp.ok) {
        const data = await resp.json().catch(() => ({}));
        closeScanner();
        haptic('medium');
        toast('Отметили вход: ' + (data.status || 'ARRIVED'), true);
      } else {
        const text = await resp.text().catch(() => '');
        haptic('light');
        toast(`Не удалось отметить вход (${resp.status})${text ? ': ' + text : ''}`, false);
      }
    } catch (e) {
      haptic('light');
      toast('Сеть недоступна, попробуйте снова', false);
    } finally {
      scanning = false;
    }
  };

  // Подписка на событие сканера
  const onQr = (data) => {
    if (!data || !data.data) return;
    postScan(data.data);
  };

  // --- wire ---
  try {
    assertWebApp();
    ready();
    tg.onEvent('qrTextReceived', onQr);
  } catch (_) { /* no-op */ }

  scanBtn.addEventListener('click', openScanner);

  // На выгрузку снимаем подписку
  window.addEventListener('unload', () => {
    try { tg.offEvent('qrTextReceived', onQr); } catch (_) {}
  });
})();
