import { AnimatePresence, motion } from "framer-motion";
import { CUSTOMERS, productById, rupees } from "../engine/catalog";
import type { OrderSystem } from "../engine/system";
import { ProductArt } from "./ProductArt";

export interface BagLine {
  productId: string;
  quantity: number;
}

interface Props {
  open: boolean;
  onClose: () => void;
  system: OrderSystem;
  lines: BagLine[];
  onQuantity: (productId: string, quantity: number) => void;
  shopperId: string;
  onShopper: (id: string) => void;
  onCheckout: () => void;
}

const SHOPPERS = CUSTOMERS.filter((c) => c.id !== "CUST-3");

export function BagDrawer(p: Props) {
  const total = p.lines.reduce((sum, l) => sum + productById(l.productId).price * l.quantity, 0);
  const balance = p.system.wallets.get(p.shopperId) ?? 0;

  return (
    <AnimatePresence>
      {p.open && (
        <>
          <motion.div
            className="fixed inset-0 z-50 bg-black/20 backdrop-blur-[2px]"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={p.onClose}
          />
          <motion.aside
            className="fixed right-0 top-0 z-50 flex h-full w-full max-w-md flex-col bg-white shadow-2xl"
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", stiffness: 320, damping: 34 }}
          >
            <div className="flex items-center justify-between border-b border-black/5 px-6 py-4">
              <h2 className="text-xl font-semibold tracking-tight">Your Bag</h2>
              <button onClick={p.onClose} aria-label="Close bag" className="rounded-full p-1.5 text-neutral-500 hover:bg-black/5">
                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8">
                  <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
                </svg>
              </button>
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto px-6 py-5">
              {p.lines.length === 0 && <p className="pt-10 text-center text-neutral-500">Your bag is empty.</p>}
              {p.lines.map((line) => {
                const product = productById(line.productId);
                return (
                  <div key={line.productId} className="flex items-center gap-4">
                    <ProductArt product={product} size="sm" />
                    <div className="flex-1">
                      <p className="font-medium">{product.name}</p>
                      <p className="text-sm text-neutral-500">{rupees(product.price * line.quantity)}</p>
                    </div>
                    <div className="flex items-center rounded-full border border-black/10">
                      <button onClick={() => p.onQuantity(line.productId, line.quantity - 1)} className="px-2.5 py-1 text-lg leading-none" aria-label="Fewer">
                        -
                      </button>
                      <span className="w-6 text-center text-sm">{line.quantity}</span>
                      <button onClick={() => p.onQuantity(line.productId, line.quantity + 1)} className="px-2.5 py-1 text-lg leading-none" aria-label="More">
                        +
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="space-y-4 border-t border-black/5 px-6 py-5">
              <div>
                <p className="mb-2 text-xs font-medium uppercase tracking-wide text-neutral-500">Paying as</p>
                <div className="grid grid-cols-2 gap-1 rounded-xl bg-neutral-100 p-1">
                  {SHOPPERS.map((c) => (
                    <button
                      key={c.id}
                      onClick={() => p.onShopper(c.id)}
                      className={`rounded-lg px-3 py-2 text-left text-sm transition ${p.shopperId === c.id ? "bg-white shadow-sm" : "text-neutral-500"}`}
                    >
                      <span className="block font-medium text-neutral-900">{c.name}</span>
                      <span className="block text-xs">Wallet {rupees(p.system.wallets.get(c.id) ?? 0)}</span>
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex items-center justify-between text-lg font-semibold">
                <span>Total</span>
                <span>{rupees(total)}</span>
              </div>
              {total > balance && p.lines.length > 0 && (
                <p className="text-xs text-orange-600">This is more than the wallet holds, so the payment step will decline. Try it.</p>
              )}
              <button
                disabled={p.lines.length === 0}
                onClick={p.onCheckout}
                className="w-full rounded-xl bg-[#0071e3] py-3 text-[15px] font-medium text-white transition hover:bg-[#0077ed] active:scale-[0.99] disabled:bg-neutral-200 disabled:text-neutral-400"
              >
                Place order
              </button>
              <p className="text-center text-[11px] text-neutral-400">Each product becomes its own order with its own Idempotency-Key.</p>
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
}
