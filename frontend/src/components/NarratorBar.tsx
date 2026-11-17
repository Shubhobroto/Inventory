import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import type { Caption } from "../engine/narrator";

const TONE = {
  neutral: "bg-[#0071e3]/10 text-[#0066cc]",
  good: "bg-emerald-500/10 text-emerald-700",
  warn: "bg-orange-500/10 text-orange-700",
};

/** A caption bar at the bottom of the screen that narrates what the system just did. */
export function NarratorBar({ captions }: { captions: Caption[] }) {
  const [index, setIndex] = useState(captions.length - 1);
  const [open, setOpen] = useState(true);

  useEffect(() => setIndex(captions.length - 1), [captions]);

  const caption = captions[Math.min(index, captions.length - 1)];
  if (!caption) return null;

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="fixed bottom-5 left-1/2 z-40 -translate-x-1/2 rounded-full bg-neutral-900/90 px-4 py-2 text-xs font-medium text-white shadow-lg backdrop-blur"
      >
        Show narration ({captions.length})
      </button>
    );
  }

  return (
    <div className="fixed bottom-5 left-1/2 z-40 w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2">
      <div className="relative rounded-2xl border border-black/5 bg-white/85 px-5 py-4 shadow-[0_10px_40px_rgba(0,0,0,0.12)] backdrop-blur-xl">
        <button
          onClick={() => setOpen(false)}
          aria-label="Hide narration"
          className="absolute right-3 top-3 rounded-full p-1 text-neutral-400 hover:bg-black/5 hover:text-neutral-700"
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
          </svg>
        </button>
        <AnimatePresence mode="wait">
          <motion.div
            key={caption.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.25 }}
            className="pr-6"
          >
            <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${TONE[caption.tone]}`}>{caption.pattern}</span>
            <h3 className="mt-1.5 text-[15px] font-semibold tracking-tight">{caption.title}</h3>
            <p className="mt-0.5 text-[13px] leading-snug text-neutral-600">{caption.body}</p>
          </motion.div>
        </AnimatePresence>
        <div className="mt-2 flex items-center justify-end gap-1 text-xs text-neutral-400">
          <button disabled={index <= 0} onClick={() => setIndex(index - 1)} className="rounded-full px-2 py-0.5 hover:bg-black/5 disabled:opacity-30">
            {"<"}
          </button>
          <span className="tabular-nums">
            {index + 1} / {captions.length}
          </span>
          <button
            disabled={index >= captions.length - 1}
            onClick={() => setIndex(index + 1)}
            className="rounded-full px-2 py-0.5 hover:bg-black/5 disabled:opacity-30"
          >
            {">"}
          </button>
        </div>
      </div>
    </div>
  );
}
