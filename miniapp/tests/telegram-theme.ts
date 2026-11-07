import { expect, type Locator, type Page } from '@playwright/test';

export async function telegramTheme(page: Page, scheme: 'light' | 'dark') {
  await page.emulateMedia({ colorScheme: scheme });
  await page.evaluate((scheme) => {
    const telegram = window as unknown as {
      Telegram: {
        WebView: { receiveEvent: (event: string, data: unknown) => void };
      };
    };
    telegram.Telegram.WebView.receiveEvent('theme_changed', {
      theme_params: {
        bg_color: scheme === 'light' ? '#ffffff' : '#10191b',
        text_color: scheme === 'light' ? '#18271f' : '#f4f5ef',
      },
    });
  }, scheme);
  await expect(page.locator('.client-cabinet')).toHaveCSS(
    'color-scheme',
    scheme,
  );
}

export async function contrast(locator: Locator, pseudo?: string) {
  return locator.evaluate((element, pseudo) => {
    const style = getComputedStyle(element, pseudo);
    const rgb = (value: string) =>
      value
        .match(/[\d.]+/g)!
        .slice(0, 3)
        .map(Number);
    const luminance = (color: number[]) =>
      color
        .map((part) => {
          const channel = part / 255;
          return channel <= 0.04045
            ? channel / 12.92
            : ((channel + 0.055) / 1.055) ** 2.4;
        })
        .reduce(
          (sum, part, index) => sum + part * [0.2126, 0.7152, 0.0722][index],
          0,
        );
    let parent: Element | null = element;
    let background = getComputedStyle(element).backgroundColor;
    while (background === 'rgba(0, 0, 0, 0)' && parent?.parentElement) {
      parent = parent.parentElement;
      background = getComputedStyle(parent).backgroundColor;
    }
    const values = [
      luminance(rgb(style.color)),
      luminance(rgb(background)),
    ].sort((a, b) => a - b);
    return (values[1] + 0.05) / (values[0] + 0.05);
  }, pseudo);
}
