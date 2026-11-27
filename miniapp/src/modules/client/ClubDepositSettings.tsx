import { useEffect, useState } from 'react';
import { type ConfigurationView, useClubConfiguration } from './configuration.hooks';
import { CommandState, RemoteState, Shell, useUnsavedChanges } from './OnboardingShared';

type Category = { id?: string; name: string; active: boolean };
type Config = ConfigurationView & { primary: Category; distribution: boolean; extras: Category[] };
type Draft = { expectedVersion: string; primaryName: string; distribution: boolean; extras: Category[] };
type Props = {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
};

const validCategory = (item: Category) =>
  typeof item?.id === 'string' && typeof item.name === 'string' && typeof item.active === 'boolean';

function validate(view: Config) {
  return validCategory(view.primary) && view.primary.id === 'primary' &&
    typeof view.distribution === 'boolean' && Array.isArray(view.extras) && view.extras.every(validCategory);
}

function toDraft(view: Config): Draft {
  return {
    expectedVersion: view.version,
    primaryName: view.primary.name,
    distribution: view.distribution,
    extras: view.extras.map(({ id, name, active }) => ({ id, name, active })),
  };
}

export default function ClubDepositSettings({ credential, id, back, reportDirty, reportPending }: Props) {
  const state = useClubConfiguration(credential, id, '/deposit-configuration', validate);
  const { view, remote, command } = state;
  const [draft, setDraft] = useState<Draft>();
  const value = draft ?? (view && toDraft(view));
  const conflict = draft && view && draft.expectedVersion !== view.version;
  const leave = useUnsavedChanges(Boolean(draft), reportDirty);
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  useEffect(() => {
    if (state.denied) setDraft(undefined);
  }, [state.denied]);
  return (
    <Shell title="Распределение депозита" back={() => { if (leave()) back(); }} backDisabled={command.blocked} backLabel="Назад к настройкам">
      <RemoteState {...remote} />
      {value && view && (
        <form className="po-card ob-card cm-preparation" onSubmit={(event) => {
          event.preventDefault();
          if (!conflict) state.run(value, () => setDraft(undefined), 'PATCH');
        }}>
          <p>По умолчанию вся сумма депозита относится к основной категории. Включите распределение, если при посадке часть суммы нужно учитывать отдельно. Менеджер укажет суммы дополнительных категорий, а остаток автоматически попадёт в основную.</p>
          <fieldset disabled={state.blocked}>
            <label>Основная категория <input value={value.primaryName} required maxLength={80} onChange={(event) => setDraft({ ...value, primaryName: event.target.value })} /></label>
            <label><input type="checkbox" checked={value.distribution} onChange={(event) => setDraft({ ...value, distribution: event.target.checked })} /> Включить дополнительное распределение</label>
            {value.distribution && value.extras.map((item, index) => (
              <fieldset key={item.id ?? index}>
                <legend>Дополнительная категория {index + 1}</legend>
                <label>Название <input value={item.name} required maxLength={80} onChange={(event) => setDraft({
                  ...value, extras: value.extras.map((extra, position) => position === index ? { ...extra, name: event.target.value } : extra),
                })} /></label>
                <label><input type="checkbox" checked={item.active} onChange={(event) => setDraft({
                  ...value, extras: value.extras.map((extra, position) => position === index ? { ...extra, active: event.target.checked } : extra),
                })} /> Доступна при записи оплаты</label>
                {!item.id && <button type="button" className="ob-button" onClick={() => setDraft({
                  ...value, extras: value.extras.filter((_, position) => position !== index),
                })}>Убрать новую категорию</button>}
              </fieldset>
            ))}
            {value.distribution && value.extras.length < 20 && (
              <button type="button" className="ob-button" onClick={() => setDraft({
                ...value, extras: [...value.extras, { name: '', active: true }],
              })}>Добавить категорию</button>
            )}
          </fieldset>
          {conflict ? (
            <p role="alert">Справочник изменился. Сверьте черновик с текущей версией.
              <button type="button" className="ob-button" onClick={() => { if (leave()) setDraft(undefined); }}>Загрузить сохранённую версию</button>
            </p>
          ) : <button type="submit" className="po-primary" disabled={!draft || state.blocked}>Сохранить категории</button>}
          <CommandState command={command} refresh={remote.refresh} />
        </form>
      )}
    </Shell>
  );
}
