import { maskPhone } from './mask';

describe('maskPhone', () => {
  it('masks number', () => {
    expect(maskPhone('+71234567890')).toBe('*********90');
  });
});
