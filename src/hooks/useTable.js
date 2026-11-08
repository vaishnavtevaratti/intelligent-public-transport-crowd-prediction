import { useMemo, useState } from 'react';

/**
 * Client-side search, sort and pagination for admin tables.
 * columns: { key: (row) => sortable value }; searchText: (row) => string.
 */
export function useTable(rows, { columns, searchText, pageSize = 10, initialSort }) {
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState(initialSort || { key: null, dir: 'asc' });
  const [page, setPage] = useState(0);

  const processed = useMemo(() => {
    const q = query.trim().toLowerCase();
    let list = (rows || []).filter((r) => !q || searchText(r).toLowerCase().includes(q));
    if (sort.key && columns[sort.key]) {
      const get = columns[sort.key];
      list = [...list].sort((a, b) => {
        const va = get(a) ?? '';
        const vb = get(b) ?? '';
        const cmp = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb), undefined, { numeric: true });
        return sort.dir === 'asc' ? cmp : -cmp;
      });
    }
    return list;
  }, [rows, query, sort, columns, searchText]);

  const totalPages = Math.max(1, Math.ceil(processed.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = processed.slice(safePage * pageSize, safePage * pageSize + pageSize);

  return {
    query,
    setQuery: (q) => { setQuery(q); setPage(0); },
    sort,
    toggleSort: (key) => setSort((s) => ({ key, dir: s.key === key && s.dir === 'asc' ? 'desc' : 'asc' })),
    page: safePage,
    setPage,
    totalPages,
    total: processed.length,
    rows: pageRows,
  };
}
