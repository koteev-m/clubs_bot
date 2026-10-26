import { http } from '../../shared/api/http';

export async function checkPlatformOwnerAccess(
  signal: AbortSignal,
): Promise<boolean> {
  const { data } = await http.get<unknown>('/api/platform/access', { signal });
  if (
    typeof data !== 'object' ||
    data === null ||
    !('platformOwner' in data) ||
    data.platformOwner !== true
  ) {
    throw new Error('Invalid platform access response');
  }
  return true;
}
