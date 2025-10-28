interface Props {
  value: number;
}

/** Shows capacity number. */
export default function CapacityBadge({ value }: Props) {
  return <span className="px-2 py-1 bg-gray-200 rounded">{value}</span>;
}
