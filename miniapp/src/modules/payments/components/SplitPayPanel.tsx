import { openInvoice } from '../lib/openInvoice';
import { createInvoice } from '../api/payments.api';
import { useGuestStore } from '../../guest/state/guest.store';

/** Panel for initiating payment and sharing split payments. */
export default function SplitPayPanel() {
  const { guests } = useGuestStore();

  async function pay() {
    const res = await createInvoice(guests * 1000);
    openInvoice(res.data);
  }

  return (
    <button onClick={pay} className="p-2 border">
      Оплатить
    </button>
  );
}
