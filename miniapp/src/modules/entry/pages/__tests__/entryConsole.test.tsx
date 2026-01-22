import { render, screen } from '@testing-library/react';
import EntryConsole from '../EntryConsole';

describe('EntryConsole', () => {
  it('renders host tabs', () => {
    render(<EntryConsole />);
    expect(screen.getByText('Вход')).toBeTruthy();
    expect(screen.getByText('Сканер')).toBeTruthy();
    expect(screen.getByText('Лист ожидания')).toBeTruthy();
    expect(screen.getByText('Чек-лист смены')).toBeTruthy();
  });
});
