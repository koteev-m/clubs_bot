import { AdminApiError } from '../api/admin.api';

export type FieldErrors = Record<string, string>;

const errorMessages: Record<string, string> = {
  forbidden: 'Недостаточно прав',
  club_not_found: 'Клуб не найден',
  hall_not_found: 'Зал не найден',
  hall_name_conflict: 'Зал с таким названием уже существует',
  invalid_json: 'Некорректные данные',
  validation_error: 'Проверьте заполнение формы',
  request_timeout: 'Превышено время ожидания',
  internal_error: 'Ошибка сервера',
};

const detailMessages: Record<string, string> = {
  length_1_255: 'От 1 до 255 символов',
  length_1_128: 'От 1 до 128 символов',
  must_be_non_empty: 'Поле обязательно',
  must_include_field: 'Укажите хотя бы одно поле',
  invalid_zones: 'Некорректная схема',
  last_active: 'Нельзя удалить последний активный зал',
  must_be_positive: 'Некорректный идентификатор',
};

export const mapAdminErrorMessage = (error: AdminApiError): string => {
  if (error.code === 'validation_error' && error.details) {
    const detailMessage = Object.values(error.details)
      .map((value) => detailMessages[value])
      .find((value) => value);
    if (detailMessage) return detailMessage;
  }
  if (error.code && errorMessages[error.code]) {
    return errorMessages[error.code];
  }
  return error.message || 'Ошибка запроса';
};

export const mapValidationErrors = (error: AdminApiError): FieldErrors | null => {
  if (error.code === 'hall_name_conflict') {
    return { name: 'Зал с таким названием уже существует' };
  }
  if (error.code !== 'validation_error' || !error.details) {
    return null;
  }
  const mapped: FieldErrors = {};
  Object.entries(error.details).forEach(([field, value]) => {
    mapped[field] = detailMessages[value] ?? 'Некорректное значение';
  });
  return mapped;
};
