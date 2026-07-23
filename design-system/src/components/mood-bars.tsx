import type { CSSProperties } from 'react';

export interface MoodBarsProps {
  /** One value per axis, 0–100. The journal logs four: mood, dysphoria, libido, energy. */
  values: number[];
  /** Per-axis colours, cycled if shorter than `values`. */
  accents?: string[];
  /** Overall glyph height in px. */
  height?: number;
  /** Width of each bar in px. */
  barWidth?: number;
  className?: string;
  style?: CSSProperties;
}

const DEFAULT_ACCENTS = [
  'var(--primary)',
  'var(--tertiary)',
  'var(--primary-fixed-dim)',
  'var(--secondary)',
];

/**
 * The compact glyph that stands in for a journal entry — one small vertical
 * gauge per slider, filled from the bottom.
 */
export function MoodBars({
  values,
  accents = DEFAULT_ACCENTS,
  height = 44,
  barWidth = 8,
  className,
  style,
}: MoodBarsProps) {
  return (
    <div
      className={className}
      style={{ display: 'flex', alignItems: 'flex-end', gap: 5, height, flexShrink: 0, ...style }}
    >
      {values.map((v, i) => {
        const pct = Math.max(0, Math.min(100, v));
        return (
          <div
            key={i}
            style={{
              width: barWidth,
              height: '100%',
              borderRadius: 100,
              background: 'var(--surface-container-highest)',
              position: 'relative',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: 0,
                height: `${Math.max(pct, barWidth / height * 100)}%`,
                borderRadius: 100,
                background: accents[i % accents.length],
              }}
            />
          </div>
        );
      })}
    </div>
  );
}
