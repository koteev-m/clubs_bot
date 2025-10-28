/** Legend for table status colors. */
export default function HallLegend() {
  return (
    <div className="flex space-x-4 text-sm">
      <div className="flex items-center space-x-1">
        <span className="w-3 h-3 bg-green-500 inline-block" />
        <span>Free</span>
      </div>
      <div className="flex items-center space-x-1">
        <span className="w-3 h-3 bg-yellow-500 inline-block" />
        <span>Held</span>
      </div>
      <div className="flex items-center space-x-1">
        <span className="w-3 h-3 bg-red-500 inline-block" />
        <span>Booked</span>
      </div>
    </div>
  );
}
