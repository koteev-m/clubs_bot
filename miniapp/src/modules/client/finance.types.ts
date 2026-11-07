import type { ConfigurationView } from './configuration.hooks';

export type FinanceItem = {
  id: string;
  name: string;
  enabled: boolean;
  orderIndex: number;
};
export type RevenueArticle = FinanceItem & {
  groupId: string;
  includeInTotal: boolean;
  showSeparately: boolean;
};
export type FinanceView = ConfigurationView & {
  bracelets: FinanceItem[];
  revenueGroups: FinanceItem[];
  revenueArticles: RevenueArticle[];
};
export const validFinance = (view: FinanceView) =>
  [view.bracelets, view.revenueGroups, view.revenueArticles].every(
    (items) =>
      Array.isArray(items) &&
      items.every(
        (item) =>
          /^[1-9][0-9]*$/.test(item.id) &&
          typeof item.name === 'string' &&
          typeof item.enabled === 'boolean' &&
          Number.isInteger(item.orderIndex),
      ),
  ) &&
  view.revenueArticles.every(
    (item) =>
      view.revenueGroups.some((group) => group.id === item.groupId) &&
      typeof item.includeInTotal === 'boolean' &&
      typeof item.showSeparately === 'boolean',
  );
