import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import {
  RemoteState,
  useTelegramClosingConfirmation,
  useUnsavedChanges,
} from './OnboardingShared';
import { useClubRequestScope } from './clubRequestScope';
import {
  authorityLost,
  errorMessage,
  OnboardingError,
  onboardingRequest,
} from './onboarding.api';
import { useRemote } from './onboarding.hooks';
import type { ClientSummary, ClubProfile } from './onboarding.types';
import {
  dateFormat,
  isBookingAction,
  permissions,
  permits,
  seatingAvailable,
  tableName,
} from './clubNightDesk.types';
import type {
  Action,
  Booking,
  Categories,
  Command,
  Desk,
  Hall,
  Intent,
  Method,
  Mode,
  Night,
  Payment,
  Receipt,
  SessionAction,
  Table,
  TableStatus,
} from './clubNightDesk.types';
import {
  BookingForm,
  BookingsList,
  newBookingDraft,
  bookingSuccess,
} from './ClubNightDeskBookings';
import type { BookingDraft } from './ClubNightDeskBookings';
import type { BookingAction } from './clubNightDesk.types';
import { ShiftOverview, WorkspaceHeader, WorkspaceMoney, WorkspaceNavigation } from './ClubNightWorkspaceViews';
import type { WorkspaceLocation, WorkspaceTab } from './ClubNightWorkspaceViews';
import './clubNightDesk.css';

type Draft = {
  action: SessionAction;
  night: Night;
  hall: Pick<Hall, 'id' | 'name'>;
  table: Table;
  observedPaymentIds: string;
  priorPayment: boolean;
  categories: Categories;
  mode: Mode;
  method: Method | '';
  cash?: string;
  amount: string;
  extras: Record<string, string>;
  guests: string;
  note: string;
};
type Props = {
  credential: string;
  profile: ClubProfile;
  reportDirty?: (dirty: boolean) => void;
  reportPending?: (pending: boolean) => void;
  reportBack?: (handler?: () => boolean) => void;
  openClub?: (id: string) => void;
  menu?: { label: string; run: () => void }[];
  financialReport?: React.ReactNode;
  workspaceMemory?: Map<string, WorkspaceLocation>;
};
const tableStatus: Record<TableStatus, string> = {
  free: 'Свободен',
  reserved: 'Забронирован',
  seated: 'Занят',
  stopped: 'Продажи остановлены',
  unavailable: 'Недоступен',
};
const modeLabels: Record<Mode, string> = {
  DEPOSIT: 'Депозит',
  BILL: 'По счёту',
  CLUB: 'От клуба',
};
const methodLabels: Record<Method, string> = {
  CASH: 'Наличные',
  CARD: 'Карта',
  MIXED: 'Комбинированная',
};
const blockedReasons: Record<string, string> = {
  no_current_night: 'Сейчас нет открытой операционной ночи.',
  ambiguous_night: 'Сервер не смог однозначно определить текущую ночь.',
  missing_night_mapping:
    'Для этого клуба требуется проверка существующих связей ночи и событий. Обратитесь к управляющему.',
  working_night_unprepared:
    'Ночь ещё не подготовлена. Проверьте расписание и наличие столов в действующем зале. При конфликте событий обратитесь к управляющему.',
  working_tables_unprepared:
    'Для этой ночи пока нет доступных столов. Проверьте сохранённые залы и столы в настройках.',
};
const moneyWhole = new Intl.NumberFormat('ru-RU');
const decimalPattern = /^\d{1,10}(?:[.,]\d{0,2})?$/;
const minorPattern = /^-?\d{1,15}$/;

function moneyInput(value: string): bigint | null {
  if (!decimalPattern.test(value)) return null;
  const [whole, fraction = ''] = value.split(/[.,]/);
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
}
function amountLabel(value: string | bigint | undefined) {
  if (value === undefined || !minorPattern.test(value.toString()))
    return 'Сумма неизвестна';
  const signed = BigInt(value);
  const amount = signed < 0n ? -signed : signed;
  return (
    (signed < 0n ? '−' : '') +
    moneyWhole.format(amount / 100n) +
    ',' +
    (amount % 100n).toString().padStart(2, '0')
  );
}
function commandPermission(action: Action) {
  if (isBookingAction(action)) return permissions.booking;
  return action === 'top_up' ? permissions.topUp : permissions[action];
}
function hasRecordedPayment(table: Table) {
  const total = (table.payments ?? []).reduce(
    (sum, payment) =>
      minorPattern.test(payment.amountMinor)
        ? sum + BigInt(payment.amountMinor)
        : sum,
    0n,
  );
  return total > 0n;
}
function needsMoney(draft: Draft) {
  return (
    draft.action === 'top_up' ||
    (draft.action === 'seat' &&
      draft.mode === 'DEPOSIT' &&
      !draft.priorPayment)
  );
}
function getAllocation(draft: Draft) {
  const amount = moneyInput(draft.amount);
  let extraTotal = 0n;
  let extraValid = true;
  const extras: Record<string, string> = {};
  for (const category of draft.categories.extras) {
    const input = draft.extras[category.id] || '';
    if (!input) continue;
    const part = moneyInput(input);
    if (part === null) {
      extraValid = false;
      continue;
    }
    if (part > 0n) extras[category.id] = part.toString();
    extraTotal += part;
  }
  const remaining =
    amount === null || !extraValid || amount < extraTotal
      ? null
      : amount - extraTotal;
  return { amount, extras, remaining, extraTotal, extraValid };
}
function splitTender(draft: Draft, total: bigint | null) {
  if (draft.method !== 'MIXED') return null;
  const cash = moneyInput(draft.cash ?? '');
  return cash !== null && total !== null && cash > 0n && cash < total
    ? { cash, card: total - cash }
    : null;
}

function tenderRemainder(total?: string, cash?: string) {
  if (!total || !cash || !minorPattern.test(total) || !minorPattern.test(cash))
    return;
  const card = BigInt(total) - BigInt(cash);
  if (BigInt(cash) > 0n && card > 0n) return card.toString();
}

function tenderLabel(
  method?: Method | null,
  cashMinor?: string,
  cardMinor?: string,
) {
  if (method === 'MIXED')
    return (
      methodLabels.MIXED +
      ' · наличными ' +
      amountLabel(cashMinor) +
      ' · картой ' +
      amountLabel(cardMinor)
    );
  return method ? methodLabels[method] : 'способ не указан';
}

function validDesk(view: Desk, profile: ClubProfile) {
  return (
    view?.clubId === profile.id &&
    view.clientId === profile.clientId &&
    /^\d+$/.test(view.actorId) &&
    Array.isArray(view.permissions) &&
    permits(view, permissions.read) &&
    Array.isArray(view.halls) &&
    view.halls.every(({ tables }) => Array.isArray(tables)) &&
    Array.isArray(view.bookings) &&
    view.categories?.primary &&
    Array.isArray(view.categories.extras) &&
    (view.night === null ||
      (typeof view.night?.id === 'string' &&
        /^\d{4}-\d{2}-\d{2}$/.test(view.night.businessDate) &&
        Number.isFinite(Date.parse(view.night.opensAt)) &&
        Number.isFinite(Date.parse(view.night.closesAt))))
  );
}
function recorded(receipt: Receipt, body: Command) {
  const card = tenderRemainder(body.amountMinor, body.cashMinor);
  return (
    receipt?.action === body.action &&
    receipt.nightId === body.nightId &&
    receipt.tableId === body.tableId &&
    (isBookingAction(body.action)
      ? receipt.sessionId === null &&
        (body.action === 'stop_sales' ||
          body.action === 'resume_sales' ||
          (typeof receipt.bookingId === 'string' &&
            (!body.bookingId || body.bookingId === receipt.bookingId)))
      : typeof receipt.sessionId === 'string' &&
        (!body.sessionId || receipt.sessionId === body.sessionId)) &&
    (!body.amountMinor ||
      (receipt.amountMinor === body.amountMinor &&
        receipt.method === body.method &&
        (body.method !== 'MIXED' ||
          (receipt.cashMinor === body.cashMinor &&
            card !== undefined &&
            receipt.cardMinor === card))))
  );
}
function readIntent(key: string): Intent | undefined {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return;
    const intent = JSON.parse(raw) as Intent;
    if (
      /^[a-zA-Z0-9._:-]{8,128}$/.test(intent.key) &&
      typeof intent.label === 'string' &&
      typeof intent.businessDate === 'string' &&
      typeof intent.body?.nightId === 'string' &&
      typeof intent.body.tableId === 'string' &&
      (['seat', 'release', 'top_up'].includes(intent.body.action) ||
        isBookingAction(intent.body.action))
    )
      return intent;
    sessionStorage.removeItem(key);
  } catch {
    // The active request remains recoverable in memory if browser storage is unavailable.
  }
}
function saveStored<T>(key: string, data?: T) {
  try {
    if (data) sessionStorage.setItem(key, JSON.stringify(data));
    else sessionStorage.removeItem(key);
  } catch {
    // Telegram WebViews can forbid storage. In-memory recovery stays available.
  }
}
function paymentIds(table: Table) {
  return (table.payments ?? [])
    .map(({ operationId }) => operationId)
    .sort()
    .join(',');
}

function readDraft(key: string, view: Desk): Draft | undefined {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return;
    const draft = JSON.parse(raw) as Draft;
    if (
      !['seat', 'release', 'top_up'].includes(draft.action) ||
      !permits(view, commandPermission(draft.action)) ||
      (needsMoney(draft) && !permits(view, permissions.payments)) ||
      typeof draft.hall?.id !== 'string' ||
      typeof draft.table?.id !== 'string' ||
      typeof draft.night?.id !== 'string' ||
      !Array.isArray(draft.categories?.extras)
    ) {
      saveStored(key);
      return;
    }
    const active =
      view.night?.id === draft.night.id
        ? view.halls
            .find(({ id }) => id === draft.hall.id)
            ?.tables.find(({ id }) => id === draft.table.id)
        : undefined;
    if (
      active &&
      paymentIds(active) === draft.observedPaymentIds &&
      permits(view, permissions.payments)
    )
      draft.table.payments = active.payments;
    return draft;
  } catch {
    saveStored(key);
  }
}

export default function ClubNightDesk(props: Props) {
  const { profile, credential } = props;
  return (
    <NightDesk
      key={profile.clientId + ':' + profile.id + ':' + credential}
      {...props}
    />
  );
}

function NightDesk({
  credential, profile, reportDirty, reportPending, reportBack, openClub, menu = [], financialReport, workspaceMemory,
}: Props) {
  const path = '/api/client/clubs/' + profile.id + '/operations';
  const scope = useClubRequestScope();
  const savedLocation = workspaceMemory?.get(profile.id);
  const [selectedNight, setSelectedNight] = useState(savedLocation?.night ?? '');
  const readPath = selectedNight ? path + '/nights/' + selectedNight : path;
  const [intent, setIntent] = useState<Intent>();
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<{
    receipt: Receipt;
    intent: Intent;
  }>();
  const [problem, setProblem] = useState('');
  const [draft, setDraft] = useState<Draft>();
  const [bookingDraft, setBookingDraft] = useState<BookingDraft>();
  const [findingTable, setFindingTable] = useState<'seat' | 'booking'>();
  const [tab, setTab] = useState<WorkspaceTab>(savedLocation?.tab ?? 'shift');
  const [statusFilter, setStatusFilter] = useState<TableStatus | undefined>(savedLocation?.status);
  const [tableId, setTableId] = useState(savedLocation?.tableId);
  const [tableMap, setTableMap] = useState(savedLocation?.tableMap ?? false);
  const [formVisible, setFormVisible] = useState(true);
  const [bookingSearch, setBookingSearch] = useState(savedLocation?.search ?? '');
  const [bookingFilter, setBookingFilter] = useState(savedLocation?.filter ?? '');
  const nightChoices = useRef<Partial<Record<WorkspaceTab, string>>>({ ...savedLocation?.nights });
  const content = useRef<HTMLDivElement>(null);
  const context = useRemote<{ clients: ClientSummary[] }>(credential, openClub ? '/api/client/context' : null);
  const clubs = context.data?.clients.flatMap((client) => client.clubs ?? []) ?? [];
  const [bookingId, setBookingId] = useState(savedLocation?.bookingId);
  const storageKey = useRef<string>();
  const actor = useRef<string>();
  const running = useRef<AbortController>();
  const anchor = useRef<HTMLElement>(null);
  const formAnchor = useRef<HTMLHeadingElement>(null);
  const clearDraft = useCallback(() => {
    if (storageKey.current) saveStored(storageKey.current + ':draft');
    setDraft(undefined);
  }, []);
  const clearPrivateState = useCallback(() => {
    workspaceMemory?.delete(profile.id);
    running.current?.abort();
    running.current = undefined;
    if (storageKey.current) saveStored(storageKey.current);
    setIntent(undefined);
    setOutcome(undefined);
    clearDraft();
    setBookingId(undefined);
    setTableId(undefined);
    setBookingDraft(undefined);
    setProblem('');
    setFindingTable(undefined);
    setBusy(false);
  }, [clearDraft, profile.id, workspaceMemory]);
  const remote = useRemote<Desk>(credential, readPath, undefined, {
    retainOnForeground: true,
    onSuccess(view) {
      if (
        !validDesk(view, profile) ||
        (actor.current && actor.current !== view.actorId)
      )
        throw new OnboardingError(503, 'invalid_response');
      actor.current = view.actorId;
      setBookingId((previous) =>
        previous && view.bookings.some(({ id }) => id === previous) ? previous : undefined,
      );
      setTableId((previous) => previous && view.night &&
        view.halls.some(({ tables }) => tables.some(({ id }) => id === previous)) ? previous : undefined);

    },
    onError(error) {
      if (selectedNight && error instanceof OnboardingError && error.status === 404) {
        delete nightChoices.current[tab];
        setSelectedNight('');
        setBookingId(undefined);
        return;
      }
      if (authorityLost(error)) clearPrivateState();
    },
  });
  const view = remote.data;
  const initialized = useRef(false);
  const blocking = busy || Boolean(intent);
  const fresh = Boolean(
    view && !remote.loading && !remote.revalidating && !remote.error,
  );
  const dirty = Boolean(draft || bookingDraft || blocking);
  const tableMoney = view ? permits(view, permissions.payments) : profile.permissions.includes(permissions.payments);
  const canSeeReports = Boolean(financialReport && (view ? permits(view, 'client.club.operations.financial_reports.read') : true));
  useEffect(() => {
    if (!view || view.night || dirty || selectedNight) return;
    const next = tab === 'bookings' ? view.upcomingNights?.[0]?.id
      : tab === 'finance' ? view.recentNights?.[0]?.id : undefined;
    if (next) {
      nightChoices.current[tab] = next;
      setSelectedNight(next);
    }
  }, [tab, view, dirty, selectedNight]);
  useLayoutEffect(() => {
    if (!view || remote.error) return;
    workspaceMemory?.set(profile.id, {
      tab, night: selectedNight, nights: { ...nightChoices.current },
      search: bookingSearch, filter: bookingFilter, tableId, bookingId, status: statusFilter, tableMap,
    });
  }, [view, remote.error, workspaceMemory, profile.id, tab, selectedNight, bookingSearch, bookingFilter,
    tableId, bookingId, statusFilter, tableMap]);
  useEffect(() => {
    if (view && tab === 'finance' && !tableMoney && !canSeeReports) {
      setTab('shift');
      setSelectedNight('');
    }
  }, [view, tab, tableMoney, canSeeReports]);
  useUnsavedChanges(dirty, reportDirty);
  useTelegramClosingConfirmation(dirty);
  useLayoutEffect(() => {
    reportPending?.(blocking);
    return () => reportPending?.(false);
  }, [blocking, reportPending]);
  useLayoutEffect(() => {
    if (!view || initialized.current) return;
    initialized.current = true;
    storageKey.current = [
      'clubs.floor.pending',
      view.actorId,
      view.clientId,
      view.clubId,
    ].join(':');
    const stored = readIntent(storageKey.current);
    if (stored && permits(view, commandPermission(stored.body.action)))
      setIntent(stored);
    else if (stored) saveStored(storageKey.current);
    const restored = readDraft(storageKey.current + ':draft', view);
    if (restored) setDraft(restored);
  }, [view]);
  useLayoutEffect(() => {
    if (!storageKey.current || !draft) return;
    // Only the actor's input and its original context are retained across a WebView reload.
    // Existing bookings and payment history come from a fresh server response.
    saveStored(storageKey.current + ':draft', {
      ...draft,
      table: { ...draft.table, payments: undefined },
    });
  }, [draft]);
  useEffect(() => {
    const onDenied = (event: Event) => {
      if ((event as CustomEvent<string>).detail === credential)
        clearPrivateState();
    };
    window.addEventListener('client-authority-lost', onDenied);
    return () => {
      window.removeEventListener('client-authority-lost', onDenied);
      running.current?.abort();
    };
  }, [clearPrivateState, credential]);
  const formKey = draft
    ? [draft.night.id, draft.table.id, draft.action].join(':')
    : '';
  useEffect(() => {
    if (formKey) formAnchor.current?.focus();
  }, [formKey, formVisible]);
  useEffect(() => {
    const closesAt = view?.night?.closesAt;
    if (!closesAt) return;
    const delay = Date.parse(closesAt) - Date.now();
    if (delay <= 0) return;
    const timer = window.setTimeout(
      remote.refresh,
      Math.min(delay, 2_147_483_647),
    );
    return () => window.clearTimeout(timer);
  }, [view?.night?.closesAt, remote.refresh]);

  async function send(next: Intent) {
    if (
      !fresh ||
      !view ||
      !permits(view, commandPermission(next.body.action)) ||
      running.current
    )
      return;
    const controller = new AbortController();
    const ticket = scope?.capture(credential, path);
    const current = () =>
      running.current === controller &&
      !controller.signal.aborted &&
      (!scope || scope.current(ticket));
    running.current = controller;
    const untrack = scope?.track(ticket, () => controller.abort());
    if (!current()) {
      untrack?.();
      running.current = undefined;
      return;
    }
    setBusy(true);
    setProblem('');
    setIntent(next);
    if (storageKey.current) saveStored(storageKey.current, next);
    try {
      const receipt = await onboardingRequest<Receipt>(credential, path, {
        body: next.body,
        key: next.key,
        signal: controller.signal,
        isCurrent: current,
      });
      if (!current()) return;
      if (!recorded(receipt, next.body))
        throw new OnboardingError(0, 'invalid_receipt');
      if (storageKey.current) saveStored(storageKey.current);
      setIntent(undefined);
      clearDraft();
      setBookingDraft(undefined);
      setOutcome({ receipt, intent: next });
      setFindingTable(undefined);
      remote.refresh();
    } catch (error) {
      if (!current()) return;
      if (authorityLost(error)) {
        clearPrivateState();
        scope?.reject(ticket, error, path, 'POST');
        remote.refresh();
        return;
      }
      const uncertain =
        !(error instanceof OnboardingError) ||
        error.status === 0 ||
        error.status >= 500;
      if (uncertain) {
        setProblem('Сервер пока не подтвердил результат исходной записи.');
      } else {
        if (storageKey.current) saveStored(storageKey.current);
        setIntent(undefined);
        setProblem(commandProblem(error));
        if (error.status === 409) remote.refresh();
      }
    } finally {
      untrack?.();
      if (running.current === controller) {
        running.current = undefined;
        if (!controller.signal.aborted) setBusy(false);
      }
    }
  }

  function start(action: SessionAction, hall: Hall, table: Table) {
    if (
      !view?.night ||
      view.blocked ||
      !fresh ||
      blocking ||
      !permits(view, commandPermission(action))
    )
      return;
    if (
      (draft || bookingDraft) &&
      !window.confirm('Закрыть текущую форму без сохранения?')
    )
      return;
    setBookingDraft(undefined);
    setFormVisible(true);
    setDraft({
      action,
      night: view.night,
      hall: { id: hall.id, name: hall.name },
      table,
      observedPaymentIds: paymentIds(table),
      priorPayment: hasRecordedPayment(table),
      categories: view.categories,
      mode:
        permits(view, permissions.deposit) || hasRecordedPayment(table)
          ? 'DEPOSIT'
          : 'BILL',
      method: '',
      cash: '',
      amount: '',
      extras: {},
      guests: '',
      note: '',
    });
    setFindingTable(undefined);
    setProblem('');
    setOutcome(undefined);
  }

  function set<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => current && { ...current, [key]: value });
  }
  const latestTable = view?.halls
    .find(({ id }) => id === draft?.hall.id)
    ?.tables.find(({ id }) => id === draft?.table.id);
  const sameTable = Boolean(
    draft &&
      latestTable &&
      latestTable.lastSessionId === draft.table.lastSessionId &&
      latestTable.sessionId === draft.table.sessionId &&
      latestTable.bookingId === draft.table.bookingId &&
      latestTable.status === draft.table.status &&
      paymentIds(latestTable) === draft.observedPaymentIds,
  );
  const sameNight = Boolean(draft && view?.night?.id === draft.night.id);
  const categoryChanged = Boolean(
    draft &&
      view &&
      needsMoney(draft) &&
      draft.categories.version !== view.categories.version,
  );
  const draftAllowed = Boolean(
    draft &&
      view &&
      permits(view, commandPermission(draft.action)) &&
      (!needsMoney(draft) ||
        draft.action !== 'seat' ||
        permits(view, permissions.deposit)) &&
      (!draft.priorPayment || permits(view, permissions.payments)),
  );
  const frozen =
    blocking ||
    !fresh ||
    !sameNight ||
    !sameTable ||
    categoryChanged ||
    !draftAllowed ||
    Boolean(view?.blocked);

  function submit() {
    if (!draft || !view || frozen) return;
    const { action, night, table, mode } = draft;
    const body: Command = {
      action,
      nightId: night.id,
      tableId: table.id,
      expectedLastSessionId: table.lastSessionId,
    };
    if (table.sessionId) body.sessionId = table.sessionId;
    if (action === 'seat') {
      body.mode = mode;
      if (table.bookingId) body.bookingId = table.bookingId;
      if (draft.guests) {
        if (
          !/^\d{1,3}$/.test(draft.guests) ||
          Number(draft.guests) < 1 ||
          Number(draft.guests) > table.capacity
        ) {
          setProblem(
            'Укажите фактическое число гостей или оставьте поле пустым.',
          );
          return;
        }
        body.guests = Number(draft.guests);
      }
      if (draft.note.trim()) body.note = draft.note.trim();
    }
    if (needsMoney(draft)) {
      const { amount, remaining, extras } = getAllocation(draft);
      if (
        amount === null ||
        amount <= 0n ||
        remaining === null ||
        !draft.method
      ) {
        setProblem(
          'Укажите положительную полученную сумму, способ оплаты и проверьте распределение.',
        );
        return;
      }
      body.amountMinor = amount.toString();
      body.method = draft.method;
      if (draft.method === 'MIXED') {
        const split = splitTender(draft, amount);
        if (!split) {
          setProblem('Обе части комбинированной оплаты должны быть больше нуля.');
          return;
        }
        body.cashMinor = split.cash.toString();
      }
      body.categoryVersion = draft.categories.version;
      body.extras = extras;
    }
    void send({
      key: crypto.randomUUID(),
      body,
      label: tableName(table),
      businessDate: night.businessDate,
    });
  }

  function refreshCategories() {
    if (!view || !draft) return;
    const kept = Object.fromEntries(
      view.categories.extras.map(({ id }) => [id, draft.extras[id] || '']),
    );
    if (
      Object.entries(draft.extras).some(([id, value]) => value && !(id in kept))
    ) {
      setProblem(
        'Одна из заполненных категорий отключена. Очистите её сумму в исходной форме, прежде чем обновить справочник.',
      );
      return;
    }
    setDraft({ ...draft, categories: view.categories, extras: kept });
    setProblem('');
  }
  function chooseTab(next: WorkspaceTab) {
    setTab(next);
    setBookingId(undefined);
    setFindingTable(undefined);
    setFormVisible(false);
    if (!dirty) setSelectedNight(nightChoices.current[next] ?? '');
    content.current?.scrollTo?.(0, 0);
  }

  useLayoutEffect(() => {
    reportBack?.(() => {
      if (blocking) return true;
      if (formVisible && (draft || bookingDraft)) {
        setFormVisible(false);
        return true;
      }
      if (bookingId || tableId) {
        if (bookingId) setBookingId(undefined);
        else setTableId(undefined);
        return true;
      }
      if (tab !== 'shift') {
        setTab('shift');
        if (!dirty) setSelectedNight('');
        return true;
      }
      return false;
    });
    return () => reportBack?.();
  }, [reportBack, blocking, formVisible, draft, bookingDraft, bookingId, tableId, tab, dirty]);

  function returnToTables(kind: 'seat' | 'booking' = 'seat') {
    chooseTab('tables');
    setSelectedNight(selectedNight);
    setFindingTable(kind);
    window.requestAnimationFrame(() =>
      anchor.current?.scrollIntoView?.({ block: 'start' }),
    );
  }
  function openBooking(action: BookingAction, table: Table, booking?: Booking) {
    if (
      !view?.night ||
      !fresh ||
      blocking ||
      view.blocked ||
      !permits(view, permissions.booking)
    )
      return;
    if (
      (draft || bookingDraft) &&
      !window.confirm('Закрыть текущую форму без сохранения?')
    )
      return;
    clearDraft();
    setFormVisible(true);
    setBookingDraft(
      newBookingDraft(action, view.night, table, booking, profile.timezone),
    );
    setProblem('');
    setOutcome(undefined);
  }
  function runBooking(body: Command, table: Table, night: Night) {
    void send({
      key: crypto.randomUUID(),
      body,
      label: tableName(table),
      businessDate: night.businessDate,
    });
  }
  const unavailable = blocking || !fresh || Boolean(view?.blocked);

  const releasedTable = (outcome?.receipt.action === 'cancel_booking' ||
    (outcome?.receipt.action === 'move_booking' && outcome.intent.body.destinationTableId)) &&
    view?.night?.id === outcome.receipt.nightId && !view?.future && !view?.historical
    ? view?.halls.flatMap((hall) => hall.tables.map((table) => ({ hall, table })))
      .find(({ table }) => table.id === outcome.receipt.tableId && table.status === 'free' && !table.salesStopped)
    : undefined;
  const showingForm = formVisible && Boolean(draft || bookingDraft);
  const nightOptions = tab === 'finance' ? view?.recentNights ?? [] : view?.upcomingNights ?? [];
  const canPick = Boolean(view?.night && !view.blocked && permits(view, permissions.seat));
  const showQuickSeat = tab === 'shift' && canPick && !showingForm && !bookingId;
  return (
    <section className="night-desk night-workspace" aria-label="Рабочая ночь">
      <WorkspaceHeader name={profile.name} view={view} timezone={profile.timezone}
        menu={[...menu, { label: 'Обновить', run: remote.refresh }]} clubs={clubs} chooseClub={openClub} />
      <div className="night-work-content" ref={content}>
      <RemoteState {...remote} />
      {remote.revalidating && (
        <p role="status">Проверяем доступ и состояние столов…</p>
      )}
      {intent && !busy && (
        <div className="po-notice" role="alert">
          <p>
            Неизвестно, сохранена ли исходная запись. {intent.label} ·{' '}
            {dateFormat(intent.businessDate)}.
          </p>
          {intent.body.amountMinor && (
            <p>
              {amountLabel(intent.body.amountMinor)} ·{' '}
              {tenderLabel(
                intent.body.method,
                intent.body.cashMinor,
                tenderRemainder(intent.body.amountMinor, intent.body.cashMinor),
              )}
            </p>
          )}
          <button
            type="button"
            className="ob-button"
            disabled={!fresh}
            onClick={() => void send(intent)}
          >
            Проверить исходную запись
          </button>
        </div>
      )}
      {busy && <p role="status">Сохраняем исходную операцию…</p>}
      {problem && !draft && !bookingDraft && (
        <p className="po-notice" role="alert">
          {problem}
        </p>
      )}
      {outcome && (
        <div className="po-notice" role="status">
          <strong>
            {outcome.intent.label} ·{' '}
            {isBookingAction(outcome.receipt.action)
              ? bookingSuccess(outcome.receipt.action)
              : outcome.receipt.action === 'release'
                ? 'стол освобождён'
                : outcome.receipt.action === 'top_up'
                  ? 'доплата записана'
                  : 'гости посажены'}
          </strong>
          {view &&
            permits(view, permissions.payments) &&
            outcome.receipt.amountMinor &&
            outcome.receipt.method && (
              <p>
                Записано {amountLabel(outcome.receipt.amountMinor)} ·{' '}
                {tenderLabel(
                  outcome.receipt.method,
                  outcome.receipt.cashMinor,
                  outcome.receipt.cardMinor,
                )}
              </p>
            )}
          {releasedTable && view && permits(view, permissions.seat) && (
            <button type="button" className="ob-button po-primary" disabled={unavailable}
              onClick={() => start('seat', releasedTable.hall, releasedTable.table)}>Посадить других гостей</button>
          )}
          {(outcome.receipt.action === 'seat' ||
            outcome.receipt.action === 'top_up') && (
            <button
              type="button"
              className="ob-button"
              onClick={() => returnToTables()}
            >
              Следующий стол
            </button>
          )}
        </div>
      )}
      {formVisible && draft && (
        <DeskForm
          draft={draft}
          set={set}
          heading={formAnchor}
          canRecordDeposit={Boolean(view && permits(view, permissions.deposit))}
          canReadPayments={Boolean(view && permits(view, permissions.payments))}
          frozen={frozen}
          blocking={blocking}
          problem={problem}
          sameNight={sameNight}
          sameTable={sameTable}
          categoryChanged={categoryChanged}
          submit={submit}
          refreshCategories={refreshCategories}
          close={() => {
            clearDraft();
            setProblem('');
          }}
        />
      )}
      {formVisible && bookingDraft && (
        <BookingForm
          draft={bookingDraft}
          view={view}
          timezone={profile.timezone}
          fresh={!unavailable}
          blocked={blocking}
          problem={problem}
          set={setBookingDraft}
          execute={runBooking}
          close={() => {
            setBookingDraft(undefined);
            setProblem('');
          }}
        />
      )}
      {dirty && !showingForm && !blocking && (
        <button type="button" className="ob-button" onClick={() => setFormVisible(true)}>Вернуться к форме</button>
      )}
      {!showingForm && !bookingId && (tab === 'bookings' || tab === 'finance') &&
        (nightOptions.length > 0 || selectedNight) && (tab !== 'finance' || tableMoney) && (
        <label className="night-desk-field">Ночь
          <select value={selectedNight} disabled={dirty} onChange={(event) => {
            nightChoices.current[tab] = event.target.value;
            setSelectedNight(event.target.value);
            setProblem('');
          }}>
            <option value="">Текущая ночь</option>
            {selectedNight && !nightOptions.some((night) => night.id === selectedNight) &&
              <option value={selectedNight}>{view?.night ? dateFormat(view.night.businessDate) : 'Выбранная ночь'}</option>}
            {nightOptions.map((night) => <option value={night.id} key={night.id}>{dateFormat(night.businessDate)}</option>)}
          </select>
        </label>
      )}
      {!showingForm && tab !== 'finance' && view?.blocked &&
        (tab !== 'shift' || view.blocked !== 'no_current_night') &&
        <p className="po-notice">{blockedReasons[view.blocked] ?? 'Операции этой ночи пока недоступны.'}</p>}
      {!showingForm && tab === 'shift' && view && !bookingId && (
        <ShiftOverview view={view} timezone={profile.timezone}
          showTables={(status) => { chooseTab('tables'); setStatusFilter(status); }}
          openBooking={setBookingId} showBookings={() => chooseTab('bookings')} />
      )}
      {!showingForm && tab === 'finance' && <>
        {view && tableMoney && <WorkspaceMoney view={view} timezone={profile.timezone} amount={amountLabel} />}
        {canSeeReports && financialReport}
      </>}
      {!showingForm && view && !view.historical && (
        <>
          {tab === 'tables' && !bookingId && (
            <section ref={anchor} aria-label="Залы и столы">
              <div className="night-work-section-title"><h3>Столы</h3>
                <button type="button" className="ob-button" onClick={() => setTableMap(!tableMap)}>{tableMap ? 'Список' : 'Схема'}</button>
              </div>
              {statusFilter && <button type="button" className="ob-button" onClick={() => setStatusFilter(undefined)}>Все столы</button>}
              {permits(view, permissions.booking) && <button type="button" className="ob-button"
                onClick={() => returnToTables('booking')} disabled={unavailable}>Новая бронь</button>}
              {findingTable && (
                <p role="status">
                  {findingTable === 'booking'
                    ? 'Выберите свободный стол для новой брони.'
                    : 'Выберите свободный стол или стол с подтверждённой бронью.'}
                </p>
              )}
              {view.halls.length === 0 && (
                <p>Для этой ночи нет доступных залов.</p>
              )}
              {view.halls.map((hall) => (
                <section key={hall.id} className="night-desk-hall">
                  <h3>{hall.name}</h3>
                  {hall.tables.length === 0 && <p>В зале нет столов.</p>}
                  {tableMap && hall.tables.some(({ x, y }) => x !== undefined && y !== undefined) && (
                    <div className="night-work-map" aria-label={'Схема · ' + hall.name}>
                      {hall.tables.filter(({ x, y, status }) => x !== undefined && y !== undefined &&
                        (!statusFilter || status === statusFilter)).map((table) => (
                        <button key={table.id} type="button" data-status={table.status} title={tableName(table)}
                          style={{ left: `clamp(22px, ${(table.x ?? 0) * 100}%, calc(100% - 22px))`, top: `clamp(22px, ${(table.y ?? 0) * 100}%, calc(100% - 22px))` }}
                          onClick={() => setTableId(table.id)} aria-label={tableName(table) + ' · ' + tableStatus[table.status]}>
                          {table.number}</button>
                      ))}
                    </div>
                  )}
                  <ul className="night-desk-tables">
                    {hall.tables.filter((table) => (!statusFilter || table.status === statusFilter) &&
                       (!tableMap || tableId === table.id || table.x === undefined || findingTable)).map((table) => (
                      <li key={table.id} className="night-desk-table">
                        <span className="night-desk-table-heading">
                          <button type="button" className="night-work-table-button" aria-expanded={tableId === table.id}
                            onClick={() => setTableId(tableId === table.id ? undefined : table.id)}>{tableName(table)}</button>
                          <span data-status={table.status}>
                            {tableStatus[table.status]}
                          </span>
                        </span>
                        {(tableId === table.id || findingTable) && <>
                        <p className="po-muted">Мест: {table.capacity}
                          {table.seatedGuests !== undefined && ' · Фактически сели: ' + table.seatedGuests}</p>
                        {permits(view, permissions.payments) && (
                          <PaymentHistory payments={table.payments} />
                        )}
                        <div className="night-desk-actions">
                          {table.status === 'free' &&
                            !table.salesStopped &&
                            permits(view, permissions.booking) && (
                              <button
                                type="button"
                                className="ob-button"
                                aria-label={
                                  'Забронировать · ' + tableName(table)
                                }
                                onClick={() =>
                                  openBooking('create_booking', table)
                                }
                                disabled={unavailable}
                              >
                                Забронировать
                              </button>
                            )}
                          {table.status !== 'unavailable' &&
                            permits(view, permissions.booking) && (
                              <button
                                type="button"
                                className="ob-button"
                                onClick={() =>
                                  openBooking(
                                    table.salesStopped ||
                                      table.status === 'stopped'
                                      ? 'resume_sales'
                                      : 'stop_sales',
                                    table,
                                  )
                                }
                                disabled={unavailable}
                              >
                                {table.salesStopped ||
                                table.status === 'stopped'
                                  ? 'Возобновить продажи'
                                  : 'Остановить продажи'}
                              </button>
                            )}
                          {seatingAvailable(table) &&
                            permits(view, permissions.seat) && (
                              <button
                                type="button"
                                className="ob-button"
                                aria-label={'Посадить · ' + tableName(table)}
                                onClick={() => start('seat', hall, table)}
                                disabled={
                                  blocking || !fresh || Boolean(view.blocked)
                                }
                              >
                                Посадить
                              </button>
                            )}
                          {table.sessionId &&
                            permits(view, permissions.release) && (
                              <button
                                type="button"
                                className="ob-button"
                                aria-label={'Освободить · ' + tableName(table)}
                                onClick={() => start('release', hall, table)}
                                disabled={
                                  blocking || !fresh || Boolean(view.blocked)
                                }
                              >
                                Освободить
                              </button>
                            )}
                          {table.sessionId &&
                            permits(view, permissions.topUp) && (
                              <button
                                type="button"
                                className="ob-button"
                                aria-label={'Доплата · ' + tableName(table)}
                                onClick={() => start('top_up', hall, table)}
                                disabled={
                                  blocking || !fresh || Boolean(view.blocked)
                                }
                              >
                                Доплата
                              </button>
                            )}
                          {table.bookingId && (
                            <button
                              type="button"
                              className="ob-button"
                              aria-label={'Бронь · ' + tableName(table)}
                              onClick={() => {
                                setBookingId(table.bookingId ?? undefined);
                              }}
                            >
                              Бронь
                            </button>
                          )}
                        </div>
                        </>}
                      </li>
                    ))}
                  </ul>
                </section>
              ))}
            </section>
          )}
          {(tab === 'bookings' || bookingId) && (
            <BookingsList
              view={view}
              search={bookingSearch} setSearch={setBookingSearch} filter={bookingFilter} setFilter={setBookingFilter}
              timezone={profile.timezone}
              disabled={unavailable}
              selected={bookingId}
              select={setBookingId}
              open={openBooking}
              seat={(hall, table) => start('seat', hall, table)}
            />
          )}
        </>
      )}
      </div>
      <footer className="night-work-footer">
        {showQuickSeat && <button type="button" className="ob-button po-primary night-work-seat"
          onClick={() => returnToTables()} disabled={unavailable}>Посадить гостей</button>}
        <WorkspaceNavigation tab={tab} finance={tableMoney || canSeeReports} select={chooseTab} />
      </footer>
    </section>
  );
}

function DeskForm({
  draft,
  canRecordDeposit,
  canReadPayments,
  frozen,
  blocking,
  problem,
  sameNight,
  sameTable,
  categoryChanged,
  heading,
  set,
  submit,
  refreshCategories,
  close,
}: {
  draft: Draft;
  canRecordDeposit: boolean;
  canReadPayments: boolean;
  frozen: boolean;
  blocking: boolean;
  problem: string;
  sameNight: boolean;
  sameTable: boolean;
  categoryChanged: boolean;
  heading: React.RefObject<HTMLHeadingElement>;
  set: <K extends keyof Draft>(key: K, value: Draft[K]) => void;
  submit: () => void;
  refreshCategories: () => void;
  close: () => void;
}) {
  const money = needsMoney(draft);
  const { amount, remaining, extraTotal, extraValid } = getAllocation(draft);
  const split = splitTender(draft, amount);
  const allocationError =
    !extraValid || (amount !== null && extraTotal > amount);
  const modeOptions =
    canRecordDeposit || draft.priorPayment
      ? (['DEPOSIT', 'BILL', 'CLUB'] as const)
      : (['BILL', 'CLUB'] as const);
  const title =
    draft.action === 'release'
      ? 'Освобождение стола'
      : draft.action === 'top_up'
        ? 'Доплата по посадке'
        : 'Посадка гостей';
  const submitLabel =
    draft.action === 'release'
      ? 'Освободить стол'
      : draft.action === 'top_up'
        ? 'Записать доплату'
        : money
          ? 'Посадить и записать оплату'
          : 'Посадить гостей';
  return (
    <form
      className="night-desk-form night-work-seat-form"
      aria-label={title}
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <div className="night-work-form-heading">
        <h3 tabIndex={-1} ref={heading}>{title}</h3>
        <button type="button" className="ob-button" aria-label="Закрыть форму"
          disabled={blocking} onClick={close}>Закрыть</button>
      </div>
      <p>
        <strong>
          {draft.hall.name} · {tableName(draft.table)}
        </strong>{' '}
        · {dateFormat(draft.night.businessDate)}
      </p>
      {blocking && (
        <p className="po-muted">
          Исходная операция ожидает подтверждения сервера.
        </p>
      )}
      {!sameNight && !blocking && (
        <p className="po-notice" role="alert">
          Открыта другая ночь. Ввод сохранён для сверки с исходным столом.
        </p>
      )}
      {sameNight && !sameTable && !blocking && (
        <p className="po-notice" role="alert">
          Состояние исходного стола изменилось. Сверьте операцию со списком
          столов. Ввод сохранён.
        </p>
      )}
      {categoryChanged && !blocking && (
        <div className="po-notice" role="alert">
          <p>Категории изменились. Сверьте суммы перед продолжением.</p>
          <button
            type="button"
            className="ob-button"
            onClick={refreshCategories}
          >
            Загрузить актуальные категории
          </button>
        </div>
      )}
      {draft.action === 'release' && (
        <p>
          Стол станет свободным. Посадка и записанные поступления останутся в
          истории.
        </p>
      )}
      {canReadPayments && draft.action !== 'release' && (
        <PaymentHistory payments={draft.table.payments} />
      )}
      <fieldset disabled={blocking}>
        {draft.action === 'seat' && (
          <>
            <label className="ob-field">
              Режим посадки
              <select
                value={draft.mode}
                onChange={(event) => {
                  set('mode', event.target.value as Mode);
                  set('method', '');
                  set('cash', '');
                }}
              >
                {modeOptions.map((mode) => (
                  <option value={mode} key={mode}>
                    {modeLabels[mode]}
                  </option>
                ))}
              </select>
            </label>
            {draft.mode === 'DEPOSIT' && !money && (
              <p className="po-muted">
                Депозит уже записан. После посадки можно отдельно записать
                доплату.
              </p>
            )}
          </>
        )}
        {money && (
          <>
            <label className="ob-field">
              <span>Фактически получено <span aria-hidden="true">· ₽</span></span>
              <input
                aria-label="Фактически получено"
                inputMode="decimal"
                value={draft.amount}
                onChange={(event) => set('amount', event.target.value)}
                placeholder="0,00"
                maxLength={13}
                required
              />
            </label>
            <fieldset className="night-desk-radio">
              <legend>Способ оплаты</legend>
              {(['CASH', 'CARD', 'MIXED'] as const).map((method) => (
                <label key={method}>
                  <input
                    type="radio"
                    aria-label={methodLabels[method]}
                    name="floor-payment-method"
                    value={method}
                    required
                    checked={draft.method === method}
                    onChange={() => {
                      set('method', method);
                      set('cash', '');
                    }}
                  />{' '}
                  {method === 'MIXED' ? 'Наличные + карта' : methodLabels[method]}
                </label>
              ))}
            </fieldset>
            {draft.method === 'MIXED' && (
              <div className="night-desk-allocation night-work-tender-split">
                <label className="ob-field">
                  Наличными
                  <input
                    inputMode="decimal"
                    value={draft.cash ?? ''}
                    onChange={(event) => set('cash', event.target.value)}
                    placeholder="0,00"
                    maxLength={13}
                    required
                  />
                </label>
                <p><span>Картой</span><strong>{split ? amountLabel(split.card) + ' ₽' : '—'}</strong></p>
                {!split && draft.cash && amount !== null && (
                  <p className="po-muted">
                    Укажите положительную сумму наличными меньше общей суммы.
                  </p>
                )}
              </div>
            )}
            <div className="night-desk-allocation">
              {draft.categories.extras.length > 0 && (
                <details open={Object.values(draft.extras).some(Boolean) || undefined}>
                  <summary>Распределить депозит</summary>
                  {draft.categories.extras.map((category) => (
                <label className="ob-field" key={category.id}>
                  {category.name}
                  <input
                    inputMode="decimal"
                    value={draft.extras[category.id] || ''}
                    placeholder="0,00"
                    maxLength={13}
                    onChange={(event) =>
                      set('extras', {
                        ...draft.extras,
                        [category.id]: event.target.value,
                      })
                    }
                  />
                </label>
                  ))}
                </details>
              )}
              <dl className="po-details">
                <div>
                  <dt>{draft.categories.primary.name}</dt>
                  <dd>{remaining === null ? '—' : amountLabel(remaining)}</dd>
                </div>
                <div>
                  <dt>Итого</dt>
                  <dd>{amount === null ? '—' : amountLabel(amount)}</dd>
                </div>
              </dl>
              {allocationError && (
                <p role="alert">
                  Вводите неотрицательные суммы. Распределение не может
                  превышать полученную сумму.
                </p>
              )}
            </div>
          </>
        )}
        {draft.action === 'seat' && (
          <details>
            <summary>Число гостей и комментарий (по желанию)</summary>
            <label className="ob-field">
              Фактически сели
              <input
                type="number"
                min={1}
                max={Math.min(999, draft.table.capacity)}
                value={draft.guests}
                onChange={(event) => set('guests', event.target.value)}
              />
            </label>
            <label className="ob-field">
              Комментарий
              <textarea
                value={draft.note}
                maxLength={500}
                onChange={(event) => set('note', event.target.value)}
              />
            </label>
          </details>
        )}
      </fieldset>
      {problem && (
        <p className="po-notice" role="alert">
          {problem}
        </p>
      )}
      <div className="night-work-form-submit">
        <button
          type="submit"
          className="ob-button po-primary"
          disabled={
            frozen ||
            (money &&
              (remaining === null ||
                amount === 0n ||
                !draft.method ||
                (draft.method === 'MIXED' && !split)))
          }
        >
          {submitLabel}
        </button>
      </div>
    </form>
  );
}

function PaymentHistory({ payments }: { payments?: Payment[] }) {
  if (!payments?.length) return null;
  return (
    <div className="night-desk-payments">
      <p className="po-muted">Ранее записано</p>
      <ul>
        {payments.map((payment) => (
          <li key={payment.operationId}>
            {amountLabel(payment.amountMinor)} ·{' '}
            {tenderLabel(payment.method, payment.cashMinor, payment.cardMinor)}
          </li>
        ))}
      </ul>
    </div>
  );
}

function commandProblem(error: OnboardingError) {
  const messages: Record<string, string> = {
    version_conflict:
      'Данные изменились. Форма сохранена для сверки с исходным столом.',
    invalid_transition:
      'Текущее состояние стола или брони не позволяет выполнить действие. Проверьте исходные данные.',
    operations_table_not_enabled:
      'Для этого стола операционное управление пока недоступно.',
    booking_cutoff_passed: 'Время бронирования этой ночи истекло. Выберите другую ночь.',
    shift_not_open: 'Смена ещё не подготовлена. Обновите экран или обратитесь к управляющему.',
    shift_report_closed: 'Смена закрыта. Денежную запись изменить нельзя.',
    entry_denied: 'Для гостя действует запрет входа. Посадка недоступна.',
    table_occupied: 'Этот стол уже занят. Проверьте занятость.',
  };
  return messages[error.code] ?? errorMessage(error);
}
