import axios from 'axios';

interface ApiErrorResponse {
  error?: {
    code?: string;
  };
}

function getApiErrorResponse(error: unknown): { data?: ApiErrorResponse } | undefined {
  if (!axios.isAxiosError<ApiErrorResponse>(error)) return undefined;
  return error.response;
}

export function getApiErrorCode(error: unknown): string | undefined {
  const response = getApiErrorResponse(error);
  return response?.data?.error?.code;
}

export function getApiErrorInfo(error: unknown): { code: string; hasResponse: boolean } {
  const response = getApiErrorResponse(error);
  return {
    code: response?.data?.error?.code ?? 'error',
    hasResponse: Boolean(response),
  };
}

export function isRequestCanceled(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false;
  return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
}
