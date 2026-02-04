import { describe, expect, it } from 'vitest';
import { validateShiftReportDraft } from '../FinanceShiftScreen';

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
});
