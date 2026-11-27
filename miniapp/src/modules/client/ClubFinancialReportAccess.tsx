import { useEffect } from 'react';
import { useCommand, useRemote } from './onboarding.hooks';
import { OnboardingError } from './onboarding.api';
import { CommandState, RemoteState, Shell } from './OnboardingShared';

type Grant = { id: string; name: string; active: boolean; version: string };
type View = { clubId: string; clientId: string; assignments: Grant[] };

export default function ClubFinancialReportAccess({ id, credential, back, reportPending }: {
  id: string; credential: string; back: () => void; reportPending: (value: boolean) => void;
}) {
  const path = '/api/client/clubs/' + id + '/operations/financial-report-access';
  const check = (view: View) => {
    if (view.clubId !== id || !Array.isArray(view.assignments)) throw new OnboardingError(502, 'invalid_response');
  };
  const remote = useRemote<View>(credential, path, undefined, { onSuccess: check });
  const command = useCommand<View>(credential, { onError: () => remote.refresh() });
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  return (
    <Shell title="Допуск к финансовым итогам" back={back} backLabel="Назад к клубу" backDisabled={command.blocked}>
      <RemoteState {...remote} />
      <CommandState command={command} refresh={remote.refresh} />
      {remote.data?.assignments.length === 0 && <p>Управляющие клуба пока не назначены.</p>}
      {remote.data?.assignments.map((item) => (
        <section className="po-card ob-card" key={item.id}>
          <h2>{item.name}</h2>
          <p>{item.active ? 'Доступ к закрытым финансовым итогам выдан.' : 'Доступ к закрытым финансовым итогам закрыт.'}</p>
          <button type="button" className="ob-button" disabled={command.blocked || remote.revalidating} onClick={() => {
            command.run(path, { assignmentId: item.id, active: !item.active, expectedVersion: item.version }, (view) => {
              check(view);
              remote.refresh();
            }, 'PATCH');
          }}>{item.active ? 'Отозвать доступ' : 'Выдать доступ'}</button>
        </section>
      ))}
    </Shell>
  );
}
