interface Props {
  label: string;
  value: string | number;
}

/** Displays inline label-value pair. */
export default function InlineStat({ label, value }: Props) {
  return (
    <div className="flex space-x-1 text-sm">
      <span className="text-gray-500">{label}:</span>
      <span>{value}</span>
    </div>
  );
}
