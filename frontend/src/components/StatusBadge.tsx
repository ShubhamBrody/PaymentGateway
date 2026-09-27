export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${status.toLowerCase().replaceAll('_', '-')}`}>{status.replaceAll('_', ' ')}</span>
}
