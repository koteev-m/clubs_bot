import { describe, expect, it } from 'vitest';
import { getApiErrorCode, getApiErrorInfo } from './error';

describe('api error helpers', () => {
  it('returns undefined code and no response for non-axios error', () => {
    const error = new Error('Boom');

    expect(getApiErrorCode(error)).toBeUndefined();
    expect(getApiErrorInfo(error)).toEqual({ code: 'error', hasResponse: false });
  });

  it('returns code and response state for axios error with response code', () => {
    const error = {
      isAxiosError: true,
      response: {
        data: {
          error: {
            code: 'invalid_qr',
          },
        },
      },
    };

    expect(getApiErrorCode(error)).toBe('invalid_qr');
    expect(getApiErrorInfo(error)).toEqual({ code: 'invalid_qr', hasResponse: true });
  });
});
