import { Link } from 'react-router-dom';
import { MapPinOff } from 'lucide-react';
import { EmptyState } from '../components/ui/ui';

export default function NotFound() {
  return (
    <div className="card">
      <EmptyState icon={MapPinOff} title="Page not found" message="The page you are looking for does not exist."
        action={<Link className="btn btn-primary" to="/dashboard">Go to dashboard</Link>} />
    </div>
  );
}
