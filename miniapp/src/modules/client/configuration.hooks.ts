import { useEffect, useRef, useState } from 'react';
import { authorityLost, OnboardingError } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';

export type ConfigurationView = {
  clubId: string;
  clientId: string;
  version: string;
  canWrite: boolean;
};

/** A refused read fences every older command, even after access is restored. */
export function useClubConfiguration<T extends ConfigurationView>(
  credential: string,
  id: string,
  suffix: string,
  validate: (view: T) => boolean,
) {
  const path = '/api/client/clubs/' + id + suffix;
  const [view, setView] = useState<T>();
  const [denied, setDenied] = useState(false);
  const latest = useRef<T>();
  const clientId = useRef<string>();
  const authority = useRef({ denied: false, epoch: 0, canWrite: false });
  const cancelRead = useRef<() => void>(() => undefined);
  const check = (received: T) => {
    if (
      received.clubId !== id ||
      !/^[1-9][0-9]*$/.test(received.clientId) ||
      (clientId.current && received.clientId !== clientId.current) ||
      !/^[1-9][0-9]*$/.test(received.version) ||
      typeof received.canWrite !== 'boolean' ||
      !validate(received)
    )
      throw new OnboardingError(404, 'not_found');
  };
  const onError = (error: unknown) => {
    if (!authorityLost(error)) return;
    authority.current = {
      denied: true,
      epoch: authority.current.epoch + 1,
      canWrite: false,
    };
    cancelRead.current();
    latest.current = undefined;
    setView(undefined);
    setDenied(true);
  };
  const remote = useRemote<T>(credential, path, undefined, {
    retainOnForeground: true,
    onError,
    onSuccess: (received) => {
      check(received);
      clientId.current = received.clientId;
      authority.current.denied = false;
      // Grant revocation does not increment the configuration version.
      if (authority.current.canWrite && !received.canWrite)
        authority.current.epoch++;
      authority.current.canWrite = received.canWrite;
      setDenied(false);
      if (
        !latest.current ||
        BigInt(received.version) >= BigInt(latest.current.version)
      ) {
        latest.current = received;
        setView(received);
      } else {
        latest.current = { ...latest.current, canWrite: received.canWrite };
        setView(latest.current);
      }
    },
  });
  cancelRead.current = remote.cancel;
  const command = useCommand<T>(credential, { onError });
  const versionConflict =
    command.error instanceof OnboardingError &&
    command.error.code === 'version_conflict';
  const refresh = remote.refresh;
  useEffect(() => {
    if (versionConflict && !denied) refresh();
  }, [versionConflict, denied, refresh]);
  useEffect(() => {
    if (!denied && authorityLost(command.error)) command.reset();
  }, [denied, command]);
  const run = (body: object, saved: (received: T) => void, method = 'POST') => {
    if (
      !view?.canWrite ||
      denied ||
      remote.loading ||
      remote.revalidating ||
      remote.error ||
      command.blocked
    )
      return;
    const epoch = authority.current.epoch;
    command.run(
      path,
      body,
      (received) => {
        if (authority.current.denied || epoch !== authority.current.epoch)
          return false;
        check(received);
        if (
          latest.current &&
          BigInt(received.version) < BigInt(latest.current.version)
        ) {
          refresh();
          return false;
        }
        latest.current = received;
        setView(received);
        saved(received);
        refresh();
        return true;
      },
      method,
    );
  };
  return {
    view: denied ? undefined : view,
    denied,
    remote,
    command,
    versionConflict,
    run,
    blocked:
      denied ||
      !view?.canWrite ||
      remote.loading ||
      remote.revalidating ||
      Boolean(remote.error) ||
      command.blocked,
  };
}
