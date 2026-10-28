import { useCallback, useEffect, useState } from 'react';
import { authorityLost } from './onboarding.api';
import { useCommand } from './onboarding.hooks';
import { Invitation } from './onboarding.types';

/** Memory owned by the authorization gate, never browser storage or history. */
export function useInvitationDraft(credential: string, hideLink = false) {
  const [clientName, setClientName] = useState('');
  const [recipientLabel, setRecipientLabel] = useState('');
  const [invitation, storeInvitation] = useState<Invitation>();
  const command = useCommand<Invitation>(credential);
  const setInvitation = useCallback((value: Invitation) => {
    const safe = { ...value } as Invitation & { token?: string };
    // The API's token duplicates the link; do not retain an undeclared raw field.
    delete safe.token;
    storeInvitation(safe);
  }, []);
  const forgetLink = useCallback(() => {
    storeInvitation((value) =>
      value?.invitationLink ? { ...value, invitationLink: undefined } : value,
    );
  }, []);
  useEffect(() => {
    setClientName('');
    setRecipientLabel('');
    storeInvitation(undefined);
  }, [credential]);
  useEffect(() => {
    if (authorityLost(command.error)) {
      setClientName('');
      setRecipientLabel('');
      storeInvitation(undefined);
    }
  }, [command.error]);
  useEffect(() => {
    if (hideLink && invitation?.invitationLink) forgetLink();
  }, [hideLink, invitation, forgetLink]);
  const reset = () => {
    if (command.blocked) return;
    setClientName('');
    setRecipientLabel('');
    storeInvitation(undefined);
    command.reset();
  };
  return {
    clientName,
    setClientName,
    recipientLabel,
    setRecipientLabel,
    invitation,
    setInvitation,
    command,
    forgetLink,
    reset,
  };
}

export type InvitationDraft = ReturnType<typeof useInvitationDraft>;
