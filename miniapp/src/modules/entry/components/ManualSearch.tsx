import { useState } from 'react';
import { http } from '../../../shared/api/http';

/** Manual search component for guest lists. */
export default function ManualSearch() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<string[]>([]);

  async function search() {
    const res = await http.get<string[]>(`/api/checkin/search?q=${encodeURIComponent(query)}`);
    setResults(res.data);
  }

  return (
    <div className="space-y-2">
      <input value={query} onChange={(e) => setQuery(e.target.value)} className="border p-2 w-full" />
      <button onClick={search} className="p-2 border">
        Search
      </button>
      <ul>
        {results.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
    </div>
  );
}
