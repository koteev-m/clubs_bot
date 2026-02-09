import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AnalyticsMetaNotice } from '../AdminAnalyticsScreen';

describe('AnalyticsMetaNotice', () => {
  it('renders caveats and incomplete data indicator', () => {
    render(
      <AnalyticsMetaNotice
        meta={{
          hasIncompleteData: true,
          caveats: ['attendance_unavailable', 'visits_unavailable'],
        }}
      />,
    );

    expect(screen.getByText('Данные неполные')).toBeTruthy();
    expect(screen.getByText('attendance_unavailable')).toBeTruthy();
    expect(screen.getByText('visits_unavailable')).toBeTruthy();
  });
});
