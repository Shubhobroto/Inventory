import { useEffect } from "react";
import { AnimatePresence, motion } from "framer-motion";

/** The full-screen "Order placed" moment after checkout. */
export function OrderPlacedSheet({ orderIds, onDone }: { orderIds: string[] | null; onDone: () => void }) {
  useEffect(() => {
    if (!orderIds) return;
    const timer = setTimeout(onDone, 2_600);
    return () => clearTimeout(timer);
  }, [orderIds, onDone]);

  return (
    <AnimatePresence>
      {orderIds && (
        <motion.div
          className="fixed inset-0 z-[60] flex items-center justify-center bg-white/70 backdrop-blur-xl"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onDone}
        >
          <motion.div
            className="flex flex-col items-center text-center"
            initial={{ scale: 0.9, y: 10 }}
            animate={{ scale: 1, y: 0 }}
            transition={{ type: "spring", stiffness: 260, damping: 22 }}
          >
            <svg viewBox="0 0 80 80" className="h-24 w-24">
              <motion.circle
                cx="40"
                cy="40"
                r="36"
                fill="none"
                stroke="#34c759"
                strokeWidth="4"
                initial={{ pathLength: 0 }}
                animate={{ pathLength: 1 }}
                transition={{ duration: 0.6, ease: "easeOut" }}
              />
              <motion.path
                d="M24 41 l11 11 l21 -23"
                fill="none"
                stroke="#34c759"
                strokeWidth="5"
                strokeLinecap="round"
                strokeLinejoin="round"
                initial={{ pathLength: 0 }}
                animate={{ pathLength: 1 }}
                transition={{ duration: 0.4, delay: 0.5, ease: "easeOut" }}
              />
            </svg>
            <h2 className="mt-5 text-3xl font-semibold tracking-tight">Order placed</h2>
            <p className="mt-2 text-neutral-500">
              {orderIds.join(", ")} accepted. Now watch {orderIds.length > 1 ? "them" : "it"} travel between the services.
            </p>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
