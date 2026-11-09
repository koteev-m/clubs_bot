import { createContext, useContext } from 'react';
import { authorityLost, OnboardingError } from './onboarding.api';

/** Request lifetime only. Server grants remain the sole source of authority. */
export function createClubRequestScope(
  credential: string,
  clubId: string,
  onDenied: (error: unknown) => void,
) {
  const prefix = '/api/client/clubs/' + clubId;
  let epoch = 0;
  let closed = false;
  const cancellations = new Set<() => void>();
  const current = (ticket: number | undefined) =>
    ticket === undefined || (!closed && ticket === epoch);
  return {
    capture(actor: string, path: string) {
      return actor === credential &&
        (path === prefix || path.startsWith(prefix + '/'))
        ? epoch
        : undefined;
    },
    current,
    track(ticket: number | undefined, cancel: () => void) {
      if (ticket === undefined) return () => {};
      if (!current(ticket)) {
        cancel();
        return () => {};
      }
      cancellations.add(cancel);
      return () => {
        cancellations.delete(cancel);
      };
    },
    reject(
      ticket: number | undefined,
      error: unknown,
      path: string,
      method: string,
    ) {
      if (ticket === undefined || !current(ticket) || !authorityLost(error))
        return;
      // These reads require club.read alone in the existing server contract.
      // A 404 from a more restricted diagnostic (team/finance/preview) can
      // concern only that resource; it does not revoke the whole club context.
      const clubRead = [
        'profile',
        'preparation',
        'calendar',
        'layout',
        'booking-rules',
      ].some((suffix) => path === prefix + '/' + suffix);
      if (
        error instanceof OnboardingError &&
        error.status === 404 &&
        method === 'GET' &&
        !clubRead
      )
        return;
      // A refused mutation quarantines cached data until a fresh profile read;
      // this never changes grants or claims permanent loss of club.read.
      // Fence synchronously before cancellation or React's next render. A
      // completed/uncancellable response must also fail this epoch check.
      closed = true;
      epoch++;
      for (const cancel of cancellations) cancel();
      cancellations.clear();
      onDenied(error);
    },
  };
}
const Context = createContext<ReturnType<
  typeof createClubRequestScope
> | null>(null);
export const ClubRequestScopeProvider = Context.Provider;
export const useClubRequestScope = () => useContext(Context);
