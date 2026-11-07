import { useEffect, useState } from 'react';
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from './OnboardingShared';
import { useClubConfiguration } from './configuration.hooks';
import {
  FinanceItem,
  FinanceView,
  RevenueArticle,
  validFinance,
} from './finance.types';
import { OnboardingError } from './onboarding.api';

type Entity = 'bracelet' | 'group' | 'article';
type Draft = {
  entity: Entity;
  version: string;
  id?: string;
  name: string;
  orderIndex: string;
  groupId: string;
  includeInTotal: boolean;
  showSeparately: boolean;
};
const titles: Record<Entity, string> = {
  bracelet: 'Типы браслетов',
  group: 'Категории выручки',
  article: 'Статьи выручки',
};
const addLabels: Record<Entity, string> = {
  bracelet: 'Добавить тип браслета',
  group: 'Добавить категорию',
  article: 'Добавить статью',
};

export default function ClubFinance({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
}) {
  const config = useClubConfiguration<FinanceView>(
    credential,
    id,
    '/finance/template',
    validFinance,
  );
  const { view, remote, command } = config;
  const [draft, setDraft] = useState<Draft>();
  const [actionAt, setActionAt] = useState('');
  const [validation, setValidation] = useState('');
  const leave = useUnsavedChanges(
    Boolean(draft) || command.unknown,
    reportDirty,
  );
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  useEffect(() => {
    if (config.denied) {
      setDraft(undefined);
      setValidation('');
    }
  }, [config.denied]);
  const stale = Boolean(draft && view && draft.version !== view.version);
  const begin = (entity: Entity, item?: FinanceItem) => {
    if (!view || config.blocked || (draft && !leave())) return;
    command.reset();
    setActionAt('editor');
    setValidation('');
    const article =
      entity === 'article' ? (item as RevenueArticle | undefined) : undefined;
    const items =
      entity === 'bracelet'
        ? view.bracelets
        : entity === 'group'
          ? view.revenueGroups
          : view.revenueArticles;
    setDraft({
      entity,
      version: view.version,
      id: item?.id,
      name: item?.name ?? '',
      orderIndex: String(item?.orderIndex ?? items.length),
      groupId: article?.groupId ?? '',
      includeInTotal: article?.includeInTotal ?? false,
      showSeparately: article?.showSeparately ?? false,
    });
  };
  const save = () => {
    if (!draft || !view || config.blocked || stale) return;
    if (
      !draft.name.trim() ||
      draft.name.trim().length > 120 ||
      !/^\d+$/.test(draft.orderIndex) ||
      Number(draft.orderIndex) > 10000 ||
      (draft.entity === 'article' && !draft.groupId)
    ) {
      setValidation(
        'Укажите название до 120 символов, порядок от 0 до 10000 и категорию для статьи.',
      );
      return;
    }
    setActionAt('editor');
    config.run(
      {
        expectedVersion: draft.version,
        entity: draft.entity,
        action: draft.id ? 'update' : 'create',
        ...(draft.id ? { id: draft.id } : {}),
        name: draft.name.trim(),
        orderIndex: Number(draft.orderIndex),
        ...(draft.entity === 'article'
          ? {
              groupId: draft.groupId,
              includeInTotal: draft.includeInTotal,
              showSeparately: draft.showSeparately,
            }
          : {}),
      },
      () => setDraft(undefined),
    );
  };
  const mutate = (
    entity: Entity,
    item: FinanceItem,
    action: 'disable' | 'reorder',
    ids?: string[],
  ) => {
    if (!view || draft || config.blocked) return;
    setActionAt(entity + item.id);
    config.run(
      {
        expectedVersion: view.version,
        entity,
        action,
        ...(ids ? { ids } : { id: item.id }),
      },
      () => undefined,
    );
  };
  const feedback = (at: string) =>
    actionAt === at &&
    (command.error instanceof OnboardingError &&
    command.error.code === 'finance_group_in_use' ? (
      <p role="alert">
        В категории есть включённые статьи. Сначала перенесите или отключите их.
      </p>
    ) : (
      <CommandState command={command} refresh={remote.refresh} />
    ));
  const section = (entity: Entity, items: FinanceItem[]) => (
    <section
      className="po-card ob-card"
      aria-label={titles[entity]}
      key={entity}
    >
      <h2>{titles[entity]}</h2>
      {items.length === 0 && <p>Пока нет сохранённых настроек.</p>}
      {items.map((item, index) => (
        <div className="ob-config-item" key={item.id}>
          <h3>{item.name}</h3>
          <p>
            {item.enabled ? 'Включено в шаблон' : 'Отключено'} · Порядок:{' '}
            {item.orderIndex}
          </p>
          {entity === 'article' &&
            (() => {
              const article = item as RevenueArticle;
              return (
                <p>
                  {
                    view?.revenueGroups.find(
                      (group) => group.id === article.groupId,
                    )?.name
                  }{' '}
                  ·{' '}
                  {article.includeInTotal
                    ? 'Входит в общую выручку'
                    : 'Не входит в общую выручку'}{' '}
                  ·{' '}
                  {article.showSeparately
                    ? 'Показывается отдельно'
                    : 'Без отдельного отображения'}
                </p>
              );
            })()}
          {view?.canWrite && item.enabled && (
            <div className="ob-actions">
              <button
                type="button"
                className="ob-button"
                disabled={config.blocked}
                onClick={() => begin(entity, item)}
              >
                Изменить {item.name}
              </button>
              <button
                type="button"
                className="ob-button"
                disabled={config.blocked || Boolean(draft)}
                onClick={() => mutate(entity, item, 'disable')}
              >
                Отключить {item.name}
              </button>
            </div>
          )}
          {view?.canWrite && index > 0 && (
            <button
              type="button"
              className="ob-button"
              disabled={config.blocked || Boolean(draft)}
              onClick={() => {
                const ids = items.map((entry) => entry.id);
                [ids[index - 1], ids[index]] = [ids[index], ids[index - 1]];
                mutate(entity, item, 'reorder', ids);
              }}
            >
              Выше: {item.name}
            </button>
          )}
          {feedback(entity + item.id)}
        </div>
      ))}
      {view?.canWrite && (
        <button
          type="button"
          className="ob-button"
          disabled={
            config.blocked ||
            (entity === 'article' &&
              !view.revenueGroups.some((group) => group.enabled))
          }
          onClick={() => begin(entity)}
        >
          {addLabels[entity]}
        </button>
      )}
      {draft?.entity === entity && (
        <form
          className="ob-card ob-config-editor"
          onSubmit={(event) => {
            event.preventDefault();
            save();
          }}
        >
          <h3>{draft.id ? 'Изменение настройки' : addLabels[entity]}</h3>
          <fieldset disabled={config.blocked || stale}>
            <Field label="Название">
              <input
                value={draft.name}
                maxLength={120}
                onChange={(event) =>
                  setDraft({ ...draft, name: event.target.value })
                }
              />
            </Field>
            <Field label="Порядок отображения">
              <input
                inputMode="numeric"
                value={draft.orderIndex}
                onChange={(event) =>
                  setDraft({ ...draft, orderIndex: event.target.value })
                }
              />
            </Field>
            {entity === 'article' && (
              <>
                <Field label="Категория выручки">
                  <select
                    value={draft.groupId}
                    onChange={(event) =>
                      setDraft({ ...draft, groupId: event.target.value })
                    }
                  >
                    <option value="">Выберите категорию</option>
                    {view?.revenueGroups
                      .filter((group) => group.enabled)
                      .map((group) => (
                        <option key={group.id} value={group.id}>
                          {group.name}
                        </option>
                      ))}
                  </select>
                </Field>
                <label className="ob-checkbox">
                  <input
                    type="checkbox"
                    checked={draft.includeInTotal}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        includeInTotal: event.target.checked,
                      })
                    }
                  />
                  Включать в общую выручку
                </label>
                <label className="ob-checkbox">
                  <input
                    type="checkbox"
                    checked={draft.showSeparately}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        showSeparately: event.target.checked,
                      })
                    }
                  />
                  Показывать отдельно
                </label>
              </>
            )}
            <button type="submit" className="po-primary">
              Сохранить настройку
            </button>
          </fieldset>
          {validation && <p role="alert">{validation}</p>}
          {(stale || config.versionConflict) && (
            <p role="alert">
              Шаблон изменился. Сверьте сохранённые значения и откройте
              настройку заново.
            </p>
          )}
          <button
            type="button"
            className="ob-button"
            disabled={command.blocked}
            onClick={() => {
              setDraft(undefined);
              command.reset();
              setValidation('');
            }}
          >
            Отменить изменения
          </button>
          {feedback('editor')}
        </form>
      )}
    </section>
  );
  return (
    <Shell
      title="Финансовый шаблон"
      subtitle="Типы браслетов и состав отчёта клуба"
      back={() => {
        if (!command.blocked && leave()) back();
      }}
    >
      <p>
        Эти настройки определяют состав будущего отчёта. Денежные операции и
        закрытие смены здесь недоступны.
      </p>
      <RemoteState {...remote} />
      {config.denied && (
        <p role="alert">Настройки недоступны с текущими правами.</p>
      )}
      {!view && command.blocked && (
        <CommandState command={command} refresh={remote.refresh} />
      )}
      {view && (
        <>
          {!view.canWrite && (
            <p className="po-notice">
              Доступно только чтение финансового шаблона.
            </p>
          )}
          {section('bracelet', view.bracelets)}
          {section('group', view.revenueGroups)}
          {section('article', view.revenueArticles)}
          {!draft && actionAt === 'editor' && feedback('editor')}
          <button
            type="button"
            className="ob-button"
            disabled={command.blocked}
            onClick={() => {
              if (leave()) {
                setDraft(undefined);
                command.reset();
                remote.refresh();
              }
            }}
          >
            Обновить шаблон
          </button>
        </>
      )}
      <section className="po-card ob-card">
        <h2>Распределение депозитов</h2>
        <p>
          Пока недоступно: требуется согласовать каталог категорий и правила
          распределения. Статьи выручки не заменяют категории депозитов.
        </p>
      </section>
    </Shell>
  );
}
