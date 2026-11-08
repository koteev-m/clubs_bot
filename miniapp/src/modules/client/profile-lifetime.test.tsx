import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubProfile from './ClubProfile';
import type { ClubProfile as Profile } from './onboarding.types';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const profile: Profile = {
  id: '100',
  clientId: '20',
  clientName: 'Synthetic client',
  name: 'Private venue',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Private address',
  unit: '',
  version: '1',
  savedAt: '2026-11-07T00:00:00Z',
  permissions: ['client.club.read', 'client.club.profile.write'],
  availableActions: ['update'],
};
const props = {
  credential: 'synthetic-a',
  id: '100',
  home: vi.fn(),
  reportDirty: vi.fn(),
};
const response = (data: unknown, status = 200) =>
  Response.json(data, { status });
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function startSave() {
  fireEvent.change(await screen.findByLabelText('Описание клуба'), {
    target: { value: 'My pending edit' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
}
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it.each([403, 404])(
  'keeps profile denial after GET %s, late PATCH 200 and a fresh GET 503, then permits a new authorized read',
  async (status) => {
    const write = deferred();
    let readStatus = 200;
    let saved = profile;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
      if (options?.method === 'PATCH') return write.promise;
      if (!String(path).endsWith('/profile')) return response({}, 404);
      return response(
        readStatus === 200 ? saved : { code: 'not_found' },
        readStatus,
      );
    });
    render(<ClubProfile {...props} />);
    await startSave();
    readStatus = status;
    fireEvent.focus(window);
    await screen.findByText(/Доступ утрачен/);
    expect(screen.queryByText(profile.address)).toBeNull();
    await act(async () =>
      write.resolve(
        response({ ...profile, name: 'Private late name', version: '2' }),
      ),
    );
    expect(screen.queryByText('Private late name')).toBeNull();
    expect(screen.queryByText('Сохранено')).toBeNull();
    readStatus = 503;
    fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
    await screen.findByText('Не удалось загрузить данные. Повторите попытку.');
    expect(screen.queryByText(profile.address)).toBeNull();
    saved = { ...profile, name: 'Restored venue', version: '3' };
    readStatus = 200;
    fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
    await screen.findByRole('heading', { name: saved.name, level: 1 });
    expect(screen.getByText(profile.address)).toBeTruthy();
  },
);

it('ignores a pre-denial PATCH even after a new GET has restored access', async () => {
  const write = deferred();
  let allowed = true;
  let saved = profile;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH') return write.promise;
    if (!String(path).endsWith('/profile')) return response({}, 404);
    return response(
      allowed ? saved : { code: 'not_found' },
      allowed ? 200 : 404,
    );
  });
  render(<ClubProfile {...props} />);
  await startSave();
  allowed = false;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  allowed = true;
  saved = { ...profile, name: 'Fresh authority', version: '3' };
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await screen.findByRole('heading', { name: saved.name, level: 1 });
  await act(async () =>
    write.resolve(
      response({ ...profile, name: 'Obsolete authority', version: '4' }),
    ),
  );
  expect(screen.queryByText('Obsolete authority')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it.each(['current', 'older', 'restored'])(
  'keeps a write revocation from a %s GET after a late PATCH',
  async (snapshot) => {
    const write = deferred();
    let saved = { ...profile, version: '3' };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
      if (options?.method === 'PATCH') return write.promise;
      if (!String(path).endsWith('/profile')) return response({}, 404);
      return response(saved);
    });
    render(<ClubProfile {...props} />);
    await startSave();
    saved = {
      ...saved,
      version: snapshot === 'older' ? '2' : '3',
      permissions: ['client.club.read'],
      availableActions: [],
    };
    fireEvent.focus(window);
    await screen.findByText('Профиль доступен только для просмотра.');
    if (snapshot === 'restored') {
      saved = { ...profile, version: '5' };
      fireEvent.focus(window);
      await screen.findByRole('button', { name: 'Сохранить профиль' });
    }
    await act(async () =>
      write.resolve(
        response({ ...profile, name: 'Obsolete writer', version: '6' }),
      ),
    );
    expect(screen.queryByText('Obsolete writer')).toBeNull();
    if (snapshot !== 'restored')
      expect(
        screen.queryByRole('button', { name: 'Сохранить профиль' }),
      ).toBeNull();
    expect(screen.queryByText('Сохранено')).toBeNull();
    // Reauthorizing does not make a response from the previous authority epoch valid.
    saved = { ...profile, version: '5' };
    fireEvent.focus(window);
    await screen.findByRole('button', { name: 'Сохранить профиль' });
  },
);

it('permits leaving an inaccessible club after an unknown command without trapping the user', async () => {
  let allowed = true;
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  const home = vi.fn();
  const reportPending = vi.fn();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH') return response({}, 503);
    if (!String(path).endsWith('/profile')) return response({}, 503);
    return response(
      allowed ? profile : { code: 'not_found' },
      allowed ? 200 : 404,
    );
  });
  render(<ClubProfile {...props} home={home} reportPending={reportPending} />);
  await startSave();
  await screen.findByText('Результат операции пока неизвестен');
  allowed = false;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  expect(reportPending).toHaveBeenLastCalledWith(false);
  fireEvent.click(
    screen.getByRole('button', { name: 'Организация и другие заведения' }),
  );
  expect(home).toHaveBeenCalledWith(undefined);
});

it('does not let a late PATCH restore other revoked configuration capabilities', async () => {
  const write = deferred();
  const all = {
    ...profile,
    permissions: [...profile.permissions, 'client.club.finance.read'],
  };
  let saved = all;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH') return write.promise;
    if (!String(path).endsWith('/profile')) return response({}, 503);
    return response(saved);
  });
  const openFinance = vi.fn();
  render(<ClubProfile {...props} openFinance={openFinance} />);
  await screen.findByRole('button', { name: 'Финансовые настройки' });
  await startSave();
  saved = profile;
  fireEvent.focus(window);
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Финансовые настройки' }),
    ).toBeNull(),
  );
  await act(async () => write.resolve(response({ ...all, version: '2' })));
  expect(
    screen.queryByRole('button', { name: 'Финансовые настройки' }),
  ).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it.each(['club', 'identity'])(
  'fences a late private PATCH after a %s switch',
  async (context) => {
    const write = deferred();
    let signal: AbortSignal | undefined;
    const other = {
      ...profile,
      id: '200',
      clientId: '30',
      name: 'Other club',
      address: 'Other address',
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
      if (options?.method === 'PATCH') {
        signal = options.signal as AbortSignal;
        return write.promise;
      }
      if (!String(path).endsWith('/profile')) return response({}, 404);
      return response(String(path).includes('/200/') ? other : profile);
    });
    const view = render(<ClubProfile {...props} />);
    await startSave();
    view.rerender(
      <ClubProfile
        {...props}
        id={context === 'club' ? '200' : '100'}
        credential={context === 'identity' ? 'synthetic-b' : props.credential}
      />,
    );
    await screen.findByRole('heading', {
      name: context === 'club' ? other.name : profile.name,
      level: 1,
    });
    await act(async () =>
      write.resolve(
        response({ ...profile, name: 'Old private result', version: '2' }),
      ),
    );
    expect(signal?.aborted).toBe(true);
    expect(screen.queryByText('Old private result')).toBeNull();
    expect(screen.queryByDisplayValue('My pending edit')).toBeNull();
    if (context === 'club')
      expect(screen.queryByText(profile.address)).toBeNull();
  },
);

it('fences a delayed GET when the club changes', async () => {
  const old = deferred();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path) => {
    if (String(path).includes('/100/profile')) return old.promise;
    if (String(path).includes('/200/profile'))
      return response({ ...profile, id: '200', name: 'New context' });
    return response({}, 404);
  });
  const view = render(<ClubProfile {...props} />);
  view.rerender(<ClubProfile {...props} id="200" />);
  await screen.findByRole('heading', { name: 'New context', level: 1 });
  await act(async () => old.resolve(response(profile)));
  expect(
    screen.queryByRole('heading', { name: profile.name, level: 1 }),
  ).toBeNull();
});

it('aborts a GET begun before the PATCH confirms denial', async () => {
  const lateRead = deferred();
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH')
      return response({ code: 'not_found' }, 404);
    if (!String(path).endsWith('/profile')) return response({}, 404);
    return ++reads === 1 ? response(profile) : lateRead.promise;
  });
  render(<ClubProfile {...props} />);
  const input = await screen.findByLabelText('Описание клуба');
  fireEvent.change(input, { target: { value: 'My pending edit' } });
  fireEvent.focus(window);
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
  await screen.findByText(/Доступ утрачен/);
  await act(async () => lateRead.resolve(response(profile)));
  expect(screen.queryByText(profile.address)).toBeNull();
});

it('preserves the draft through a temporary read failure without asserting denial', async () => {
  let allowed = true;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path) =>
    !String(path).endsWith('/profile')
      ? response({}, 404)
      : response(allowed ? profile : {}, allowed ? 200 : 503),
  );
  render(<ClubProfile {...props} />);
  fireEvent.change(await screen.findByLabelText('Описание клуба'), {
    target: { value: 'Keep my draft' },
  });
  allowed = false;
  fireEvent.focus(window);
  await screen.findByText('Не удалось загрузить данные. Повторите попытку.');
  expect(screen.getByDisplayValue('Keep my draft')).toBeTruthy();
  expect(screen.getByText(profile.address)).toBeTruthy();
  allowed = true;
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await waitFor(() =>
    expect(
      screen.getByRole<HTMLButtonElement>('button', {
        name: 'Сохранить профиль',
      }).disabled,
    ).toBe(false),
  );
  expect(screen.getByDisplayValue('Keep my draft')).toBeTruthy();
});

it('retains the original unknown command and key across denial and successful authorization', async () => {
  let allowed = true;
  const attempts: RequestInit[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH') {
      attempts.push(options);
      return attempts.length === 1
        ? response({}, 503)
        : response({
            ...profile,
            description: 'My pending edit',
            version: '2',
          });
    }
    if (!String(path).endsWith('/profile')) return response({}, 404);
    return response(allowed ? profile : {}, allowed ? 200 : 404);
  });
  render(<ClubProfile {...props} />);
  await startSave();
  await screen.findByText('Результат операции пока неизвестен');
  allowed = false;
  fireEvent.focus(window);
  await screen.findByText(/Доступ утрачен/);
  expect(
    screen.queryByRole('button', { name: 'Повторить ту же операцию' }),
  ).toBeNull();
  allowed = true;
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
  );
  await screen.findByText('Сохранено');
  expect(attempts).toHaveLength(2);
  expect(attempts[1].body).toBe(attempts[0].body);
  expect(attempts[1].headers).toEqual(attempts[0].headers);
  expect(screen.getByDisplayValue('My pending edit')).toBeTruthy();
});

it.each(['saving', 'unknown'])(
  'keeps the profile and command key while %s instead of opening another section',
  async (outcome) => {
    const write = deferred();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const openFinance = vi.fn();
    const home = vi.fn();
    const reportPending = vi.fn();
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
      if (options?.method === 'PATCH')
        return outcome === 'saving' ? write.promise : response({}, 503);
      if (!String(path).endsWith('/profile')) return response({}, 503);
      return response({
        ...profile,
        permissions: [...profile.permissions, 'client.club.finance.read'],
      });
    });
    const view = render(
      <ClubProfile
        {...props}
        home={home}
        reportPending={reportPending}
        openFinance={openFinance}
      />,
    );
    await startSave();
    if (outcome === 'unknown')
      await screen.findByText('Результат операции пока неизвестен');
    fireEvent.click(
      screen.getByRole('button', { name: 'Финансовые настройки' }),
    );
    fireEvent.click(
      screen.getByRole('button', {
        name: 'Организация и другие заведения',
      }),
    );
    expect(openFinance).not.toHaveBeenCalled();
    expect(home).not.toHaveBeenCalled();
    expect(reportPending).toHaveBeenLastCalledWith(true);
    view.unmount();
    expect(reportPending).toHaveBeenLastCalledWith(false);
  },
);
