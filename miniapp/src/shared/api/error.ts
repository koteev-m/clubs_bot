interface ApiErrorResponse {
  error?: {
    code?: string;
  };
}

function getApiErrorResponse(error: unknown): { data?: ApiErrorResponse } | undefined {
  if (!error || typeof error !== 'object' || !('response' in error)) return undefined;
  return (error as { response?: { data?: ApiErrorResponse } }).response;
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
