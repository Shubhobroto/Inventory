import { forwardRef } from "react";
import { STORIES } from "../engine/stories";

const REPO_URL = "https://github.com/Shubhobroto/resilient-order-system";
const SPEEDS = [1, 2, 4];

interface Props {
  storyId: string;
  onStory: (id: string) => void;
  playing: boolean;
  onTogglePlay: () => void;
  onRestart: () => void;
  speed: number;
  onSpeed: (speed: number) => void;
  bagCount: number;
  bagBounce: number;
  onOpenBag: () => void;
}

export const NavBar = forwardRef<HTMLButtonElement, Props>(function NavBar(p, bagRef) {
  return (
    <header className="sticky top-0 z-40 border-b border-black/5 bg-white/75 backdrop-blur-xl">
      <div className="mx-auto flex h-14 max-w-[1400px] items-center gap-4 px-5 text-[13px]">
        <a href="#top" className="text-[15px] font-semibold tracking-tight">
          Resilient
        </a>
        <nav className="hidden gap-6 whitespace-nowrap text-neutral-500 xl:flex">
          <a href="#store" className="hover:text-neutral-900">Store</a>
          <a href="#orders" className="hover:text-neutral-900">Your orders</a>
          <a href="#under-the-hood" className="hover:text-neutral-900">Under the hood</a>
        </nav>

        <div className="ml-auto flex items-center gap-2">
          <select
            value={p.storyId}
            onChange={(e) => p.onStory(e.target.value)}
            aria-label="Story"
            className="hidden max-w-[11rem] rounded-full border border-black/10 bg-white px-3 py-1.5 text-[12px] sm:block"
          >
            {STORIES.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </select>

          <div className="hidden items-center rounded-full border border-black/10 bg-white p-0.5 sm:flex">
            <button onClick={p.onTogglePlay} className="rounded-full px-2.5 py-1 font-medium hover:bg-black/5" aria-label={p.playing ? "Pause" : "Play"}>
              {p.playing ? "Pause" : "Play"}
            </button>
            <button onClick={p.onRestart} className="rounded-full px-2.5 py-1 font-medium hover:bg-black/5">
              Restart
            </button>
            {SPEEDS.map((s) => (
              <button
                key={s}
                onClick={() => p.onSpeed(s)}
                className={`rounded-full px-2 py-1 font-medium ${p.speed === s ? "bg-neutral-900 text-white" : "hover:bg-black/5"}`}
              >
                {s}x
              </button>
            ))}
          </div>

          <a href={REPO_URL} target="_blank" rel="noopener noreferrer" className="whitespace-nowrap rounded-full px-3 py-1.5 font-medium text-[#0066cc] hover:bg-black/5">
            View code
          </a>

          <button ref={bagRef} onClick={p.onOpenBag} aria-label="Open bag" className="relative rounded-full p-1.5 hover:bg-black/5">
            <svg key={p.bagBounce} viewBox="0 0 24 24" className="bag-bounce h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6">
              <path d="M5 8h14l-1 12H6L5 8Z" strokeLinejoin="round" />
              <path d="M9 8V6a3 3 0 0 1 6 0v2" />
            </svg>
            {p.bagCount > 0 && (
              <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-[#0071e3] px-1 text-[10px] font-semibold text-white">
                {p.bagCount}
              </span>
            )}
          </button>
        </div>
      </div>
    </header>
  );
});
