import { useCallback, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { STORIES } from "./engine/stories";
import type { Product } from "./engine/catalog";
import { useSystem } from "./useSystem";
import { NavBar } from "./components/NavBar";
import { Store } from "./components/Store";
import { BagDrawer, type BagLine } from "./components/BagDrawer";
import { OrderPlacedSheet } from "./components/OrderPlacedSheet";
import { Orders } from "./components/Orders";
import { SystemView } from "./components/SystemView";
import { ChaosPanel } from "./components/ChaosPanel";
import { RawLog } from "./components/RawLog";
import { NarratorBar } from "./components/NarratorBar";

interface Flight {
  id: number;
  from: { x: number; y: number };
  to: { x: number; y: number };
}

export default function App() {
  const [storyId, setStoryId] = useState(STORIES[0].id);
  const { system, version, playing, setPlaying, speed, setSpeed, restart, captions } = useSystem(storyId);

  const [bag, setBag] = useState<BagLine[]>([]);
  const [bagOpen, setBagOpen] = useState(false);
  const [bagBounce, setBagBounce] = useState(0);
  const [shopperId, setShopperId] = useState("CUST-1");
  const [placed, setPlaced] = useState<string[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [flights, setFlights] = useState<Flight[]>([]);
  const bagButton = useRef<HTMLButtonElement>(null);
  const flightSeq = useRef(1);

  const addToBag = (product: Product, from: DOMRect) => {
    setBag((lines) => {
      const existing = lines.find((l) => l.productId === product.id);
      return existing
        ? lines.map((l) => (l.productId === product.id ? { ...l, quantity: l.quantity + 1 } : l))
        : [...lines, { productId: product.id, quantity: 1 }];
    });
    const target = bagButton.current?.getBoundingClientRect();
    if (target) {
      setFlights((f) => [
        ...f,
        {
          id: flightSeq.current++,
          from: { x: from.left + from.width / 2, y: from.top + from.height / 2 },
          to: { x: target.left + target.width / 2, y: target.top + target.height / 2 },
        },
      ]);
    }
  };

  const setQuantity = (productId: string, quantity: number) =>
    setBag((lines) => (quantity <= 0 ? lines.filter((l) => l.productId !== productId) : lines.map((l) => (l.productId === productId ? { ...l, quantity } : l))));

  const checkout = () => {
    const stamp = Date.now().toString(36);
    const ids = bag.map(
      (line, i) =>
        system.placeOrder({ idempotencyKey: `web-${stamp}-${i + 1}`, customerId: shopperId, productId: line.productId, quantity: line.quantity }).order.id,
    );
    setBag([]);
    setBagOpen(false);
    setPlaced(ids);
    setSelectedId(ids.length === 1 ? ids[0] : null);
  };

  const afterPlaced = useCallback(() => {
    setPlaced(null);
    document.getElementById("under-the-hood")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);

  const pickStory = (id: string) => {
    setSelectedId(null);
    if (id === storyId) restart();
    else setStoryId(id);
  };

  return (
    <div id="top">
      <NavBar
        ref={bagButton}
        storyId={storyId}
        onStory={pickStory}
        playing={playing}
        onTogglePlay={() => setPlaying(!playing)}
        onRestart={restart}
        speed={speed}
        onSpeed={setSpeed}
        bagCount={bag.reduce((n, l) => n + l.quantity, 0)}
        bagBounce={bagBounce}
        onOpenBag={() => setBagOpen(true)}
      />

      <section className="mx-auto max-w-[1400px] px-5 pb-12 pt-16 text-center">
        <motion.p initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="text-sm font-semibold text-[#bf4800]">
          Resilient Order Platform
        </motion.p>
        <motion.h1
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.08, duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
          className="mt-2 text-5xl font-semibold tracking-tight sm:text-7xl"
        >
          Every order. Exactly once.
        </motion.h1>
        <motion.p
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.16, duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
          className="mx-auto mt-5 max-w-2xl text-lg text-neutral-500 sm:text-xl"
        >
          Check out below and watch three microservices agree on your order through Kafka, without a single distributed transaction.
        </motion.p>
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.3 }} className="mt-7 flex flex-wrap justify-center gap-3">
          <a href="#store" className="rounded-full bg-[#0071e3] px-6 py-2.5 text-[15px] font-medium text-white hover:bg-[#0077ed]">
            Start shopping
          </a>
          <button onClick={() => pickStory("happy")} className="rounded-full px-6 py-2.5 text-[15px] font-medium text-[#0066cc] hover:underline">
            Watch a story {">"}
          </button>
        </motion.div>
      </section>

      <main className="mx-auto grid max-w-[1400px] gap-8 px-5 pb-48 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
        <div className="space-y-12">
          <Store system={system} onAdd={(p, rect) => addToBag(p, rect)} />
          <Orders system={system} selectedId={selectedId} onSelect={setSelectedId} />
        </div>

        <section id="under-the-hood" className="scroll-mt-20 space-y-5 lg:sticky lg:top-20 lg:self-start">
          <div>
            <h2 className="text-[28px] font-semibold tracking-tight">Under the hood</h2>
            <p className="text-sm text-neutral-500">
              {selectedId ? `Following order ${selectedId}. Click it again to see every order.` : "Each pill is a message. Click an order to follow just that one."}
            </p>
          </div>
          <SystemView system={system} selectedId={selectedId} />
          <Legend />
          <ChaosPanel system={system} />
          <RawLog system={system} version={version} selectedId={selectedId} />
        </section>
      </main>

      <BagDrawer
        open={bagOpen}
        onClose={() => setBagOpen(false)}
        system={system}
        lines={bag}
        onQuantity={setQuantity}
        shopperId={shopperId}
        onShopper={setShopperId}
        onCheckout={checkout}
      />
      <OrderPlacedSheet orderIds={placed} onDone={afterPlaced} />
      <NarratorBar captions={captions} />

      <AnimatePresence>
        {flights.map((f) => (
          <motion.div
            key={f.id}
            className="pointer-events-none fixed left-0 top-0 z-[70] h-4 w-4 rounded-full bg-[#0071e3] shadow-lg"
            initial={{ x: f.from.x - 8, y: f.from.y - 8, scale: 1, opacity: 1 }}
            animate={{ x: f.to.x - 8, y: f.to.y - 8, scale: 0.5, opacity: 0.9 }}
            transition={{ duration: 0.7, ease: [0.5, 0, 0.2, 1] }}
            onAnimationComplete={() => {
              setFlights((all) => all.filter((x) => x.id !== f.id));
              setBagBounce((b) => b + 1);
            }}
          />
        ))}
      </AnimatePresence>
    </div>
  );
}

function Legend() {
  const items = [
    ["bg-[#0071e3]", "Command"],
    ["bg-emerald-500", "Success reply"],
    ["bg-orange-500", "Bad news"],
    ["bg-violet-500", "Compensation"],
  ];
  return (
    <div className="flex flex-wrap gap-4 text-xs text-neutral-500">
      {items.map(([color, label]) => (
        <span key={label} className="flex items-center gap-1.5">
          <span className={`h-2.5 w-2.5 rounded-full ${color}`} /> {label}
        </span>
      ))}
    </div>
  );
}
