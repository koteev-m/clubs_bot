import { useEffect, useState } from "react";
import { useClubConfiguration } from "./configuration.hooks";
import {
  ModuleId,
  ModuleSettingsView,
  ModuleStatus,
  validModules,
} from "./modules.types";
import {
  CommandState,
  Field,
  RemoteState,
  Shell,
  useUnsavedChanges,
} from "./OnboardingShared";

const names: Record<ModuleId, string> = {
  stamps: "Штампы посещений",
  early_arrival: "Ранний приход",
  badges: "Бейджи",
  prizes: "Призы",
  contests: "Розыгрыши",
  table_loyalty: "Лояльность по столам",
  music: "Музыка",
};
const statusNames: Record<ModuleStatus, string> = {
  configured: "Правило настроено",
  disabled: "Отключено в сохранённых настройках",
  requires_setup: "Требует настройки",
  unavailable: "Пока недоступно",
};
const reasons: Record<ModuleId, string> = {
  stamps:
    "Запуск ожидает единого подтверждённого входа и правил начисления. Сохранённый флаг не отключает историю посещений.",
  early_arrival:
    "Можно подготовить правило времени. Начисление за ранний приход пока недоступно.",
  badges:
    "Есть хранение правил и порогов. Включение и выдача ожидают принятого порядка начисления после входа.",
  prizes:
    "Есть каталог призов и наградные уровни. Выдача и погашение не доступны в кабинете владельца.",
  contests:
    "Не реализованы розыгрыш, его период и каталог условий. Существующий флаг не запускает розыгрыши.",
  table_loyalty:
    "Подсчёт ночей со столом есть. Накопительная программа и безопасная модель включения пока не определены.",
  music:
    "Нет настройки включения музыки для клуба. Нужны права DJ, правила модерации и публикации. Контент не публикуется.",
};

type Props = {
  credential: string;
  id: string;
  back: () => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
};

export default function ClubModules(props: Props) {
  return <ModuleSettings key={`${props.credential}:${props.id}`} {...props} />;
}

function ModuleSettings({
  credential,
  id,
  back,
  reportDirty,
  reportPending,
}: Props) {
  const state = useClubConfiguration<ModuleSettingsView>(
    credential,
    id,
    "/modules-settings",
    validModules,
  );
  const { view, remote, command } = state;
  const [draft, setDraft] = useState<{ version: string; value: string }>();
  const value = draft?.value ?? view?.earlyWindowMinutes?.toString() ?? "";
  const dirty =
    draft !== undefined &&
    value !== (view?.earlyWindowMinutes?.toString() ?? "");
  const conflict = Boolean(draft && view && draft.version !== view.version);
  const valid =
    value === "" || (/^\d+$/.test(value) && Number(value) <= 2147483647);
  const leave = useUnsavedChanges(dirty || command.unknown, reportDirty);
  useEffect(() => {
    reportPending(command.blocked);
    return () => reportPending(false);
  }, [command.blocked, reportPending]);
  useEffect(() => {
    if (state.denied) setDraft(undefined);
  }, [state.denied]);

  return (
    <Shell
      title="Модули клуба"
      subtitle={view?.name}
      back={() => {
        if (!command.blocked && leave()) back();
      }}
      backLabel="Назад к клубу"
    >
      <RemoteState
        loading={remote.loading}
        error={remote.error}
        refresh={remote.refresh}
      />
      {state.denied && !remote.error && (
        <RemoteState
          loading={false}
          error={command.error}
          refresh={remote.refresh}
        />
      )}
      {!view && command.blocked && (
        <CommandState command={command} refresh={remote.refresh} />
      )}
      {view && (
        <>
          <p className="po-notice">
            Настройки относятся к этому клубу. Гостевые действия, выдача наград
            и публикация музыки здесь не включаются.
          </p>
          <section
            className="po-card ob-card"
            aria-labelledby="early-rule-title"
          >
            <h2 id="early-rule-title">Правило раннего прихода</h2>
            <p>
              Сохранено:{" "}
              {view.earlyWindowMinutes === null
                ? "время не задано"
                : `${view.earlyWindowMinutes} мин после начала ночи`}
              .
            </p>
            <p>
              Отсчёт идёт от начала операционной ночи. Например, начало в 00:00
              и значение 180 — ранний приход до 03:00. Пустое поле убирает общее
              правило. Отдельное правило ночи имеет приоритет.
            </p>
            <p>
              Изменение влияет на будущую оценку раннего прихода. Сохранение не
              пересчитывает историю и не выдаёт награды.
            </p>
            {view.canWrite ? (
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  if (!valid || !dirty || conflict || state.blocked) return;
                  state.run(
                    {
                      expectedVersion: draft?.version ?? view.version,
                      earlyWindowMinutes: value === "" ? null : Number(value),
                    },
                    () => setDraft(undefined),
                    "PATCH",
                  );
                }}
              >
                <Field label="Ранний приход · минут после начала ночи">
                  <input
                    inputMode="numeric"
                    value={value}
                    disabled={state.blocked}
                    aria-invalid={!valid}
                    onChange={(event) => {
                      command.reset();
                      setDraft({
                        version: draft?.version ?? view.version,
                        value: event.target.value,
                      });
                    }}
                  />
                </Field>
                {!valid && (
                  <p role="alert">
                    Введите целое неотрицательное число минут или оставьте поле
                    пустым.
                  </p>
                )}
                {conflict && (
                  <div className="po-notice" role="alert">
                    <p>
                      Правило изменено в другом окне. Ваше значение:{" "}
                      {value || "не задано"}. Сохранённое значение показано
                      выше.
                    </p>
                    <button
                      type="button"
                      className="ob-button"
                      disabled={state.blocked}
                      onClick={() => {
                        setDraft(undefined);
                        command.reset();
                      }}
                    >
                      Использовать сохранённое значение
                    </button>
                    <button
                      type="button"
                      className="ob-button"
                      disabled={state.blocked}
                      onClick={() => {
                        setDraft({ version: view.version, value });
                        command.reset();
                      }}
                    >
                      Оставить моё значение для нового сохранения
                    </button>
                  </div>
                )}
                <button
                  className="ob-button"
                  disabled={!valid || !dirty || conflict || state.blocked}
                >
                  Сохранить правило
                </button>
              </form>
            ) : (
              <p>Доступно только чтение настроек.</p>
            )}
            <CommandState command={command} refresh={remote.refresh} />
          </section>
          <section className="ob-list" aria-label="Доступность модулей">
            {view.modules.map((module) => (
              <article className="po-card ob-card" key={module.id}>
                <h2>{names[module.id]}</h2>
                <p>{statusNames[module.status]}</p>
                <p>{reasons[module.id]}</p>
                {module.savedEnabled === true && (
                  <p>
                    В прежних настройках сохранён включённый флаг. Он не
                    подтверждает готовность модуля для гостей.
                  </p>
                )}
              </article>
            ))}
          </section>
        </>
      )}
    </Shell>
  );
}
