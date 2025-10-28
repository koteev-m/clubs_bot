# Telegram Mini App

## Development

```bash
pnpm install
pnpm dev
```

Open `http://localhost:5173?mode=guest` in Telegram Web App environment.

## Build

```bash
pnpm build
```

Static files will be in `dist/` served by Ktor module on `/app` path.

## Modes

Start the Mini App with payload `mode=guest` or `mode=entry` to switch modes.

## Assets

Hall images (`src/assets/halls/club_<id>.png`) are not tracked in Git and must be provided manually for each club.

## Testing

Unit tests:
```bash
pnpm test
```
E2E tests:
```bash
pnpm e2e
```
