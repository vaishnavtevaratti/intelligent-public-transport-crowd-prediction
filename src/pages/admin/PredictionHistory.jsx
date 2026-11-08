import { Card, PageHeader } from '../../components/ui/ui';
import PredictionTable from '../../components/PredictionTable';
import { useApi } from '../../hooks/useApi';
import { routeService } from '../../services/routeService';
import { busService } from '../../services/busService';

export default function PredictionHistory() {
  const routesApi = useApi(() => routeService.getAllRoutes());
  const busesApi = useApi(() => busService.getAllBuses());
  return (
    <>
      <PageHeader title="Prediction history"
        description="Every crowd prediction stored in MySQL. Scheduled predictions run every 5 minutes for active buses and are kept for 30 days." />
      <Card flush>
        <PredictionTable routes={routesApi.data || []} buses={busesApi.data || []} pageSize={20} />
      </Card>
    </>
  );
}
