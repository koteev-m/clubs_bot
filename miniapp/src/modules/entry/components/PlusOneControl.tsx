import { http } from '../../../shared/api/http';
import { useUiStore } from '../../../shared/store/ui';

/** Button to register a plus-one guest. */
export default function PlusOneControl() {
  const { addToast } = useUiStore();
  async function plusOne() {
    await http.post('/api/checkin/plus-one');
    addToast('+1 added');
  }
  return (
    <button onClick={plusOne} className="p-2 border">
      +1
    </button>
  );
}
