import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Narrator, shopperName, WELCOME, type Caption } from "./engine/narrator";
import { loadStory, storyById } from "./engine/stories";
import type { OrderSystem } from "./engine/system";

const MAX_CAPTIONS = 60;
// A long frame (tab in the background) is capped so the system never jumps ahead in one step.
const MAX_FRAME_MS = 100;

export function useSystem(storyId: string) {
  const [system, setSystem] = useState<OrderSystem>(() => loadStory(storyById(storyId)));
  const [playing, setPlaying] = useState(true);
  const [speed, setSpeed] = useState(1);
  const [now, setNow] = useState(0);
  const [captions, setCaptions] = useState<Caption[]>([{ ...WELCOME, id: 0 }]);
  const captionSeq = useRef(1);
  const loadedStory = useRef(storyId);

  const restart = useCallback(() => {
    const story = storyById(storyId);
    setSystem(loadStory(story));
    setNow(0);
    setPlaying(true);
    setCaptions([{ id: captionSeq.current++, pattern: "Story", title: story.title, body: story.summary, tone: "neutral" }]);
  }, [storyId]);

  useEffect(() => {
    if (loadedStory.current === storyId) return;
    loadedStory.current = storyId;
    restart();
  }, [storyId, restart]);

  const version = useSyncExternalStore(
    useCallback((onChange: () => void) => system.subscribe(onChange), [system]),
    () => system.version,
  );

  useEffect(() => {
    const narrator = new Narrator((orderId) => shopperName(system.orders.get(orderId)?.customerId ?? "CUST-1"));
    return system.subscribe((e) => {
      if (e.kind !== "app") return;
      const caption = narrator.caption(e.event);
      if (caption) setCaptions((c) => [...c, { ...caption, id: captionSeq.current++ }].slice(-MAX_CAPTIONS));
    });
  }, [system]);

  useEffect(() => {
    if (!playing) return;
    let frame = 0;
    let last = performance.now();
    const loop = (ts: number) => {
      const dt = Math.min(ts - last, MAX_FRAME_MS);
      last = ts;
      system.advanceBy(dt * speed);
      setNow(system.now());
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(frame);
  }, [system, playing, speed]);

  return { system, version, now, playing, setPlaying, speed, setSpeed, restart, captions };
}
