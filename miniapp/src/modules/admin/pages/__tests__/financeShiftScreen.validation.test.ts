import { describe, expect, it } from 'vitest';
import { resolveRevenueGroupId, validateShiftReportDraft } from '../FinanceShiftScreen';

describe('validateShiftReportDraft', () => {
  it('blocks save when custom bracelet has no template id', () => {
    const result = validateShiftReportDraft({
      bracelets: [
        {
          key: 'bracelet-1',
          name: 'Custom',
          count: '1',
          enabled: true,
          hasExisting: false,
          savedToTemplate: false,
          isCustom: true,
        },
      ],
      revenueEntries: [],
    });

    expect(result.error).toBe('Сохраните браслет в шаблон перед сохранением отчета');
  });

  it('keeps group id when select value is empty or invalid', () => {
    const enabledGroups = [
      { id: 10, name: 'Group 1', enabled: true, orderIndex: 0 },
      { id: 20, name: 'Group 2', enabled: true, orderIndex: 1 },
    ];

    expect(resolveRevenueGroupId('', 10, enabledGroups)).toBe(10);
    expect(resolveRevenueGroupId('not-a-number', 10, enabledGroups)).toBe(10);
    expect(resolveRevenueGroupId('999', 10, enabledGroups)).toBe(10);
    expect(resolveRevenueGroupId('20', 10, enabledGroups)).toBe(20);
  });
});
