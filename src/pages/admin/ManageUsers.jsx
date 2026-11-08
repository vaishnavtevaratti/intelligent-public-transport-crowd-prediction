import { useState } from 'react';
import { Search, ShieldCheck, ShieldOff, Trash2, Users } from 'lucide-react';
import { Card, ConfirmDialog, EmptyState, ErrorState, PageHeader, Pagination, SkeletonRows, SortHeader } from '../../components/ui/ui';
import { useApi } from '../../hooks/useApi';
import { useTable } from '../../hooks/useTable';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { adminService } from '../../services/adminService';
import { getErrorMessage } from '../../services/api';
import { formatDateTime } from '../../utils/format';

const COLUMNS = { name: (u) => u.name, email: (u) => u.email, role: (u) => u.role, created: (u) => u.createdAt || '' };

export default function ManageUsers() {
  const { user: me } = useAuth();
  const toast = useToast();
  const usersApi = useApi(() => adminService.getUsers());
  const [roleFilter, setRoleFilter] = useState('');
  const [pending, setPending] = useState(null); // { type: 'delete' | 'role', user }
  const [busy, setBusy] = useState(false);

  const users = (usersApi.data || []).filter((u) => !roleFilter || u.role === roleFilter);
  const table = useTable(users, {
    columns: COLUMNS,
    searchText: (u) => `${u.name} ${u.email} ${u.phone || ''}`,
    initialSort: { key: 'name', dir: 'asc' },
  });

  const confirm = async () => {
    setBusy(true);
    const { type, user } = pending;
    try {
      if (type === 'delete') {
        await adminService.deleteUser(user.id);
        toast.success(`${user.name} deleted`);
      } else {
        const role = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
        await adminService.changeRole(user.id, role);
        toast.success(`${user.name} is now ${role === 'ADMIN' ? 'an administrator' : 'a passenger'}`);
      }
      usersApi.reload({ silent: true });
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setBusy(false);
      setPending(null);
    }
  };

  return (
    <>
      <PageHeader title="User management" description="View registered accounts, grant or remove administrator access, and delete accounts." />
      <Card flush>
        <div className="card-body toolbar">
          <div className="input-icon" style={{ flex: '1 1 260px' }}>
            <Search size={15} />
            <input className="input" style={{ width: '100%' }} placeholder="Search name, email or phone…" value={table.query} onChange={(e) => table.setQuery(e.target.value)} aria-label="Search users" />
          </div>
          <select className="select" value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)} aria-label="Filter by role">
            <option value="">All roles</option><option value="USER">Passengers</option><option value="ADMIN">Administrators</option>
          </select>
        </div>
        {usersApi.error ? <ErrorState message={usersApi.error} onRetry={usersApi.reload} /> : usersApi.loading ? (
          <div className="card-body"><SkeletonRows rows={5} /></div>
        ) : table.total === 0 ? <EmptyState icon={Users} title="No users match" /> : (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <SortHeader table={table} k="name">Name</SortHeader>
                    <SortHeader table={table} k="email">Email</SortHeader>
                    <th>Phone</th>
                    <SortHeader table={table} k="role">Role</SortHeader>
                    <SortHeader table={table} k="created">Registered</SortHeader>
                    <th className="actions">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((u) => {
                    const isMe = u.email === me?.email;
                    return (
                      <tr key={u.id}>
                        <td><strong>{u.name}</strong>{isMe && <span className="muted small"> (you)</span>}</td>
                        <td>{u.email}</td>
                        <td className="muted">{u.phone || '—'}</td>
                        <td><span className={`badge ${u.role === 'ADMIN' ? 'badge-admin' : 'badge-neutral'}`}>{u.role === 'ADMIN' ? 'Administrator' : 'Passenger'}</span></td>
                        <td className="muted nowrap">{u.createdAt ? formatDateTime(u.createdAt) : '—'}</td>
                        <td className="actions">
                          <button className="btn btn-ghost btn-sm" disabled={isMe} onClick={() => setPending({ type: 'role', user: u })}
                            title={isMe ? 'You cannot change your own role' : undefined}>
                            {u.role === 'ADMIN' ? <><ShieldOff size={14} /> Make passenger</> : <><ShieldCheck size={14} /> Make admin</>}
                          </button>
                          <button className="btn btn-ghost btn-sm btn-icon" disabled={isMe} onClick={() => setPending({ type: 'delete', user: u })} aria-label={`Delete ${u.name}`}>
                            <Trash2 size={15} color="var(--danger)" />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Pagination page={table.page} totalPages={table.totalPages} totalElements={table.total} onChange={table.setPage} label="users" />
          </>
        )}
      </Card>

      {pending && (
        <ConfirmDialog
          title={pending.type === 'delete' ? `Delete ${pending.user.name}?` : `Change role of ${pending.user.name}?`}
          confirmLabel={pending.type === 'delete' ? 'Delete account' : 'Change role'}
          busy={busy} onCancel={() => setPending(null)} onConfirm={confirm}
          message={pending.type === 'delete'
            ? `The account ${pending.user.email} will be permanently deleted.`
            : pending.user.role === 'ADMIN'
              ? 'This user will lose access to all administrator functions.'
              : 'This user will be able to manage buses, routes and other users.'} />
      )}
    </>
  );
}
