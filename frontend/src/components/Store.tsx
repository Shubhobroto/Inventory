import { motion } from "framer-motion";
import { PRODUCTS, rupees, type Product } from "../engine/catalog";
import type { OrderSystem } from "../engine/system";
import { ProductArt } from "./ProductArt";

interface Props {
  system: OrderSystem;
  onAdd: (product: Product, from: DOMRect) => void;
}

function stockLabel(available: number): { text: string; cls: string } {
  if (available <= 0) return { text: "Sold out", cls: "text-neutral-400" };
  if (available <= 5) return { text: `Only ${available} left`, cls: "text-orange-600" };
  return { text: "In stock", cls: "text-emerald-600" };
}

export function Store({ system, onAdd }: Props) {
  return (
    <section id="store" className="scroll-mt-20">
      <div className="mb-5 flex items-end justify-between">
        <h2 className="text-[28px] font-semibold tracking-tight">Store</h2>
        <p className="text-xs text-neutral-500">Stock comes live from the inventory service.</p>
      </div>
      <div className="grid gap-5 sm:grid-cols-2">
        {PRODUCTS.map((product, i) => {
          const available = system.stock.get(product.id) ?? 0;
          const stock = stockLabel(available);
          return (
            <motion.article
              key={product.id}
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true }}
              transition={{ duration: 0.5, delay: i * 0.06, ease: [0.22, 1, 0.36, 1] }}
              className="flex flex-col rounded-3xl bg-white p-5 shadow-[0_2px_20px_rgba(0,0,0,0.05)] transition-shadow hover:shadow-[0_8px_30px_rgba(0,0,0,0.08)]"
            >
              <ProductArt product={product} />
              <div className="mt-4 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-lg font-semibold tracking-tight">{product.name}</h3>
                  <p className="text-sm text-neutral-500">{product.tagline}</p>
                </div>
                <p className="text-[15px] font-medium">{rupees(product.price)}</p>
              </div>
              <div className="mt-4 flex items-center justify-between">
                <span className={`text-xs font-medium ${stock.cls}`}>{stock.text}</span>
                <button
                  disabled={available <= 0}
                  onClick={(e) => onAdd(product, e.currentTarget.getBoundingClientRect())}
                  className="rounded-full bg-[#0071e3] px-4 py-1.5 text-sm font-medium text-white transition hover:bg-[#0077ed] active:scale-95 disabled:bg-neutral-200 disabled:text-neutral-400"
                >
                  {available <= 0 ? "Sold out" : "Add to Bag"}
                </button>
              </div>
            </motion.article>
          );
        })}
      </div>
    </section>
  );
}
