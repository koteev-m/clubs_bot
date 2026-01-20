import { AdminApiError } from '../api/admin.api';

export type FieldErrors = Record<string, string>;

const errorMessages: Record<string, string> = {
  forbidden: 'Недостаточно прав',
  not_found: 'Данные не найдены',
  club_not_found: 'Клуб не найден',
  hall_not_found: 'Зал не найден',
  table_not_found: 'Стол не найден',
  hall_name_conflict: 'Зал с таким названием уже существует',
  table_number_conflict: 'Номер стола уже используется',
  invalid_json: 'Некорректные данные',
  validation_error: 'Проверьте заполнение формы',
  invalid_table_coords: 'Некорректные координаты стола',
  invalid_capacity: 'Некорректная вместимость',
  unsupported_media_type: 'Неподдерживаемый формат файла',
  payload_too_large: 'Слишком большой файл',
  request_timeout: 'Превышено время ожидания',
  internal_error: 'Ошибка сервера',
};

const detailMessages: Record<string, string> = {
  length_1_255: 'От 1 до 255 символов',
  length_1_128: 'От 1 до 128 символов',
  length_1_100: 'От 1 до 100 символов',
  must_be_non_empty: 'Поле обязательно',
  must_include_field: 'Укажите хотя бы одно поле',
  invalid_zones: 'Некорректная схема',
  last_active: 'Нельзя удалить последний активный зал',
  must_be_positive: 'Некорректный идентификатор',
  must_be_non_negative: 'Значение должно быть положительным',
  must_be_between_1_100: 'От 1 до 100',
  must_be_between_0_1: 'От 0 до 1',
  both_required: 'Укажите обе координаты',
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
  if (error.code === 'table_number_conflict') {
    return { tableNumber: 'Номер стола уже используется' };
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
