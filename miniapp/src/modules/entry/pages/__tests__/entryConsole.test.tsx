import { render, screen } from '@testing-library/react';
import EntryConsole from '../EntryConsole';

describe('EntryConsole', () => {
  it('renders host tabs', () => {
    render(<EntryConsole />);
    expect(screen.getByText('Вход')).toBeInTheDocument();
    expect(screen.getByText('Сканер')).toBeInTheDocument();
    expect(screen.getByText('Лист ожидания')).toBeInTheDocument();
    expect(screen.getByText('Чек-лист смены')).toBeInTheDocument();
  });
});
