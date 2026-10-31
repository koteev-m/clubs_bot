import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = resolve(root, 'dist');
const legacy = resolve(root, 'src/main/resources/miniapp');
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1:5183');
  let file;
  if (url.pathname === '/app' || url.pathname === '/app/') {
    const mode = url.searchParams.get('mode');
    file = resolve(
      mode === 'client' || mode === 'platform-owner' || mode === 'cabinet'
        ? dist
        : legacy,
      'index.html',
    );
  } else if (url.pathname.startsWith('/app/react/assets/')) {
    file = resolve(
      dist,
      'assets',
      url.pathname.slice('/app/react/assets/'.length),
    );
    if (!file.startsWith(dist + '/assets/')) file = undefined;
  } else if (url.pathname.startsWith('/app/assets/')) {
    file = resolve(legacy, 'assets', url.pathname.slice('/app/assets/'.length));
    if (!file.startsWith(legacy + '/assets/')) file = undefined;
  }
  if (!file) {
    response.writeHead(404).end();
    return;
  }
  try {
    const bytes = await readFile(file);
    const type = file.endsWith('.html')
      ? 'text/html'
      : file.endsWith('.js')
        ? 'text/javascript'
        : file.endsWith('.css')
          ? 'text/css'
          : 'application/octet-stream';
    response.writeHead(200, {
      'Content-Type': type + '; charset=utf-8',
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy':
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'",
    });
    response.end(bytes);
  } catch {
    response.writeHead(404).end();
  }
});
server.listen(5183, '127.0.0.1');
