import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';

export interface SliderProps {
  value: number;
  onChange: (next: number) => void;
  min?: number;
  max?: number;
  /** Colour of the filled portion and the thumb. Journal sliders tint each axis differently. */
  accent?: string;
  /** Colour of the unfilled track. */
  track?: string;
  /** Accessible name. */
  label?: string;
  className?: string;
  style?: CSSProperties;
}

/**
 * Chunky Material 3 slider with a pill thumb — the control behind the app's
 * "glisse 4 curseurs" mood entry.
 */
export function Slider({
  value,
  onChange,
  min = 0,
  max = 100,
  accent = 'var(--primary)',
  track,
  label,
  className,
  style,
}: SliderProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const pct = ((value - min) / (max - min)) * 100;

  const setFromX = useCallback(
    (clientX: number) => {
      const el = ref.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      const p = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
      onChange(Math.round(min + p * (max - min)));
    },
    [min, max, onChange],
  );

  useEffect(() => {
    if (!dragging) return;
    const move = (e: PointerEvent) => setFromX(e.clientX);
    const up = () => setDragging(false);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  }, [dragging, setFromX]);

  return (
    <div
      ref={ref}
      role="slider"
      aria-valuenow={value}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-label={label}
      tabIndex={0}
      onPointerDown={(e) => {
        setDragging(true);
        setFromX(e.clientX);
      }}
      onKeyDown={(e) => {
        if (e.key === 'ArrowLeft') onChange(Math.max(min, value - 1));
        if (e.key === 'ArrowRight') onChange(Math.min(max, value + 1));
      }}
      className={className}
      style={{
        position: 'relative',
        height: 40,
        display: 'flex',
        alignItems: 'center',
        cursor: 'pointer',
        touchAction: 'none',
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          height: 16,
          borderRadius: 100,
          background: track || 'var(--surface-container-highest)',
          overflow: 'hidden',
        }}
      >
        <div style={{ position: 'absolute', inset: 0, width: `${pct}%`, background: accent, borderRadius: 100 }} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: `calc(${pct}% - 9px)`,
          width: 6,
          height: 36,
          borderRadius: 100,
          background: accent,
          border: '4px solid var(--surface-container)',
          boxSizing: 'content-box',
          transition: dragging ? 'none' : 'left .08s linear',
        }}
      />
    </div>
  );
}
