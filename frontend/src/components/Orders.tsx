import { AnimatePresence, motion } from "framer-motion";
import { customerById, productById, rupees } from "../engine/catalog";
import type { OrderSystem } from "../engine/system";
import type { MessageType, Order, Packet } from "../engine/types";
import { ProductArt } from "./ProductArt";

interface Props {
  system: OrderSystem;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}

const WAITING_FOR: Record<string, string> = {
  AWAITING_INVENTORY: "Reserving stock",
  AWAITING_PAYMENT: "Charging the wallet",
  COMPENSATING: "Returning stock",
};

export const MESSAGE_LABEL: Record<MessageType, string> = {
  ReserveInventory: "Reserve stock",
  ReleaseInventory: "Release stock",
  ChargeCustomer: "Charge wallet",
  RefundCustomer: "Refund",
  InventoryReserved: "Stock reserved",
  InventoryRejected: "Out of stock",
  InventoryReleased: "Stock released",
  PaymentCompleted: "Payment done",
  PaymentFailed: "Payment declined",
  PaymentRefunded: "Refunded",
};

function headline(order: Order): { text: string; cls: string } {
  if (order.status === "CONFIRMED") return { text: "Confirmed", cls: "text-emerald-600" };
  if (order.status === "REJECTED") return { text: "Out of stock", cls: "text-orange-600" };
  if (order.status === "CANCELLED") {
    const why = order.failureReason === "TIMEOUT" ? "timed out" : order.failureReason === "INSUFFICIENT_FUNDS" ? "payment declined" : "cancelled";
    return { text: `Cancelled, ${why}`, cls: "text-orange-600" };
  }
  return { text: `${WAITING_FOR[order.saga] ?? "Processing"}\u2026`, cls: "text-[#0071e3]" };
}

const PROGRESS: Record<Order["saga"], number> = {
  AWAITING_INVENTORY: 25,
  AWAITING_PAYMENT: 60,
  COMPENSATING: 82,
  COMPLETED: 100,
  FAILED: 100,
};

function whereNow(system: OrderSystem, order: Order): string | null {
  const live = [...system.packets]
    .reverse()
    .find((p: Packet) => p.envelope.orderId === order.id && p.state !== "done");
  if (!live) return null;
  const what = MESSAGE_LABEL[live.envelope.type];
  switch (live.state) {
    case "flying":
      return `${what}: travelling through Kafka to ${live.to}`;
    case "waiting":
      return `${what}: waiting in Kafka, ${live.to} is offline`;
    case "processing":
      return `${what}: being handled by ${live.to}`;
    case "retrying":
      return `${what}: failed, retrying`;
    case "dead":
      return `${what}: parked in the dead-letter topic`;
    default:
      return null;
  }
}

export function Orders({ system, selectedId, onSelect }: Props) {
  const orders = [...system.orders.values()].reverse();
  const now = system.now();

  return (
    <section id="orders" className="scroll-mt-20">
      <h2 className="mb-5 text-[28px] font-semibold tracking-tight">Your orders</h2>
      {orders.length === 0 && (
        <p className="rounded-3xl bg-white p-8 text-center text-neutral-500 shadow-[0_2px_20px_rgba(0,0,0,0.05)]">
          No orders yet. Add something to your bag, or pick a story at the top.
        </p>
      )}
      <div className="space-y-4">
        <AnimatePresence initial={false}>
          {orders.map((order) => {
            const product = productById(order.productId);
            const head = headline(order);
            const where = whereNow(system, order);
            const failed = order.saga === "FAILED";
            const selected = selectedId === order.id;
            return (
              <motion.article
                key={order.id}
                layout
                initial={{ opacity: 0, y: -12, scale: 0.98 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                transition={{ type: "spring", stiffness: 300, damping: 28 }}
                onClick={() => onSelect(selected ? null : order.id)}
                className={`cursor-pointer rounded-3xl bg-white p-5 shadow-[0_2px_20px_rgba(0,0,0,0.05)] ring-2 transition ${selected ? "ring-[#0071e3]" : "ring-transparent hover:ring-black/5"}`}
              >
                <div className="flex items-center gap-4">
                  <ProductArt product={product} size="sm" />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs text-neutral-500">
                      Order {order.id} · {customerById(order.customerId).name} · {rupees(order.amount)}
                    </p>
                    <p className="truncate font-medium">
                      {order.quantity} x {product.name}
                    </p>
                    <p className={`text-lg font-semibold tracking-tight ${head.cls}`}>{head.text}</p>
                  </div>
                </div>

                <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-neutral-100">
                  <motion.div
                    className={`h-full rounded-full ${order.status === "CONFIRMED" ? "bg-emerald-500" : failed ? "bg-orange-400" : "bg-[#0071e3]"}`}
                    animate={{ width: `${PROGRESS[order.saga]}%` }}
                    transition={{ duration: 0.8, ease: [0.22, 1, 0.36, 1] }}
                  />
                </div>
                {where && <p className="mt-2 text-xs text-neutral-500">{where}</p>}

                <ol className="mt-4 space-y-2">
                  {order.history.map((h, i) => (
                    <motion.li
                      key={`${h.at}-${i}`}
                      initial={{ opacity: 0, x: -6 }}
                      animate={{ opacity: 1, x: 0 }}
                      className="flex items-center gap-2 text-sm"
                    >
                      <span
                        className={`flex h-4 w-4 items-center justify-center rounded-full text-[9px] text-white ${
                          h.status === "CANCELLED" || h.status === "REJECTED" ? "bg-orange-400" : "bg-emerald-500"
                        }`}
                      >
                        <svg viewBox="0 0 12 12" className="h-2.5 w-2.5" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M2.5 6.5l2.2 2.2 4.8-5" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      </span>
                      <span className="flex-1">{h.note}</span>
                      <span className="text-xs tabular-nums text-neutral-400">{(h.at / 1000).toFixed(1)}s</span>
                    </motion.li>
                  ))}
                  {!failed && order.saga !== "COMPLETED" && (
                    <li className="flex items-center gap-2 text-sm text-neutral-400">
                      <span className="h-4 w-4 animate-spin rounded-full border-2 border-neutral-200 border-t-[#0071e3]" />
                      <span className="flex-1">{WAITING_FOR[order.saga]}</span>
                      <span className="text-xs tabular-nums">{((now - order.updatedAt) / 1000).toFixed(0)}s</span>
                    </li>
                  )}
                </ol>

                <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-black/5 pt-3">
                  <span className="truncate font-mono text-[11px] text-neutral-400">Idempotency-Key: {order.idempotencyKey}</span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      system.placeOrder({
                        idempotencyKey: order.idempotencyKey,
                        customerId: order.customerId,
                        productId: order.productId,
                        quantity: order.quantity,
                      });
                    }}
                    className="rounded-full bg-neutral-100 px-3 py-1 text-xs font-medium text-neutral-700 hover:bg-neutral-200"
                  >
                    Send checkout again
                  </button>
                </div>
              </motion.article>
            );
          })}
        </AnimatePresence>
      </div>
    </section>
  );
}
