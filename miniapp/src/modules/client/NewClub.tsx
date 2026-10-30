import { useEffect, useState } from 'react';
import { onboardingRequest, authorityLost } from './onboarding.api';
import { useCommand, useRemote } from './onboarding.hooks';
import { CommandState, Field, RemoteState, Shell } from './OnboardingShared';
import { locationPresentation } from './profilePresentation';
import {
  ClubApplication,
  ClubProfile,
  LocationCity,
  NewVenueFields,
  OnboardingContext,
} from './onboarding.types';

export const blankNewVenue: NewVenueFields = {
  venueName: '',
  countryCode: '',
  cityId: '',
  address: '',
  cityQuery: '',
  cityLabel: '',
};

/** Form and command belong to ClientWorkspace memory, so navigation cannot mint a second intent. */
export default function NewClub({
  credential,
  clientId,
  value,
  onChange,
  previous,
  command,
  back,
  saved,
}: {
  credential: string;
  clientId: string;
  value: NewVenueFields;
  onChange: (value: NewVenueFields) => void;
  previous?: ClubApplication;
  command: ReturnType<typeof useCommand<ClubProfile>>;
  back: () => void;
  saved: (club: ClubProfile) => void;
}) {
  const countries = useRemote<{ countries: { code: string; name: string }[] }>(
    credential,
    '/api/client/locations',
  );
  const context = useRemote<OnboardingContext>(
    credential,
    '/api/client/clients/' + clientId + '/onboarding',
  );
  const [cities, setCities] = useState<LocationCity[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState(false);
  const [searched, setSearched] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [validation, setValidation] = useState('');
  const query = value.cityQuery ?? previous?.city ?? '';
  useEffect(() => {
    setCities([]);
    setSearched(false);
    setSearchError(false);
    if (!value.countryCode || query.trim().length < 2 || value.cityId) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setSearching(true);
      onboardingRequest<{ cities: LocationCity[]; hasMore: boolean }>(
        credential,
        '/api/client/locations/search',
        {
          body: { countryCode: value.countryCode, query: query.trim() },
          signal: controller.signal,
        },
      )
        .then((result) => {
          if (controller.signal.aborted) return;
          setCities(result.cities);
          setHasMore(result.hasMore);
          setSearched(true);
        })
        .catch((error) => {
          if (!controller.signal.aborted && !authorityLost(error))
            setSearchError(true);
        })
        .finally(() => {
          if (!controller.signal.aborted) setSearching(false);
        });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
      setSearching(false);
    };
  }, [credential, value.countryCode, query, value.cityId]);
  if (context.loading || context.error)
    return (
      <Shell title="Создать заведение" back={back}>
        <RemoteState {...context} />
      </Shell>
    );
  return (
    <Shell
      title="Создать заведение"
      subtitle="Заведение сохранится непубличным. Подключение бота и бронирование не включаются автоматически."
      back={back}
    >
      {previous && (
        <p className="po-notice">
          Использованы данные сохранённой заявки №{previous.id}. Прежний
          комментарий и исходные сведения остаются в истории. Подтвердите город
          из справочника.
        </p>
      )}
      <RemoteState {...countries} />
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (command.blocked) return;
          if (
            !value.venueName.trim() ||
            !value.address.trim() ||
            !value.countryCode ||
            !value.cityId
          ) {
            setValidation(
              'Укажите название, страну, город из списка и полный адрес.',
            );
            return;
          }
          setValidation('');
          command.run(
            '/api/client/clients/' + clientId + '/clubs',
            {
              venueName: value.venueName,
              countryCode: value.countryCode,
              cityId: value.cityId,
              address: value.address,
              ...(previous ? { applicationId: previous.id } : {}),
            },
            saved,
          );
        }}
      >
        <fieldset className="ob-fields" disabled={command.blocked}>
          <Field label="Название заведения">
            <input
              value={value.venueName}
              maxLength={255}
              required
              onChange={(event) =>
                onChange({ ...value, venueName: event.target.value })
              }
            />
          </Field>
          <Field label="Страна">
            <select
              aria-label="Страна"
              value={value.countryCode}
              required
              onChange={(event) =>
                onChange({
                  ...value,
                  countryCode: event.target.value,
                  cityId: '',
                  cityQuery: '',
                  cityLabel: '',
                })
              }
            >
              <option value="">Выберите страну</option>
              {countries.data?.countries.map((country) => (
                <option key={country.code} value={country.code}>
                  {country.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Город">
            <input
              value={query}
              required
              maxLength={128}
              placeholder="Введите название города"
              onChange={(event) =>
                onChange({
                  ...value,
                  cityQuery: event.target.value,
                  cityId: '',
                  cityLabel: '',
                })
              }
            />
          </Field>
          {searching && <p role="status">Ищем город…</p>}
          {searchError && (
            <p role="alert">
              Не удалось найти город. Измените запрос, чтобы повторить поиск.
            </p>
          )}
          {cities.length > 0 && (
            <Field label="Город и регион">
              <select
                aria-label="Город и регион"
                value={value.cityId}
                onChange={(event) => {
                  const city = cities.find(
                    (item) => item.id === event.target.value,
                  );
                  if (city)
                    onChange({
                      ...value,
                      cityId: city.id,
                      cityQuery: city.name,
                      cityLabel: locationPresentation(city.name, city.region)
                        .label,
                    });
                }}
              >
                <option value="">Выберите город и регион</option>
                {cities.map((city) => (
                  <option key={city.id} value={city.id}>
                    {locationPresentation(city.name, city.region).label}
                  </option>
                ))}
              </select>
            </Field>
          )}
          {hasMore && !value.cityId && (
            <p>Найдено много городов. Уточните название.</p>
          )}
          {searched && cities.length === 0 && !value.cityId && (
            <p role="alert">
              Город не найден. Уточните страну или написание. Если города нет в
              справочнике, обратитесь к владельцу платформы для уточнения
              местоположения.
            </p>
          )}
          {value.cityId && (
            <p role="status">
              Выбрано: {value.cityLabel}. Часовой пояс определяется по этому
              городу.
            </p>
          )}
          <Field label="Полный адрес">
            <input
              value={value.address}
              maxLength={512}
              required
              placeholder="Улица, дом, корпус и помещение, если нужны"
              onChange={(event) =>
                onChange({ ...value, address: event.target.value })
              }
            />
          </Field>
        </fieldset>
        {validation && (
          <p role="alert" className="po-notice">
            {validation}
          </p>
        )}
        <button className="po-primary" type="submit" disabled={command.blocked}>
          {command.saving ? 'Создаём заведение…' : 'Создать заведение'}
        </button>
        <CommandState command={command} />
      </form>
      <p className="po-muted">
        Города и часовые пояса:{' '}
        <a href="https://www.geonames.org/" target="_blank" rel="noreferrer">
          GeoNames
        </a>
        . Ввод обрабатывается сервером платформы.
      </p>
    </Shell>
  );
}
