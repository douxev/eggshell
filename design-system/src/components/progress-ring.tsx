import type { CSSProperties, ReactNode } from 'react';

export interface ProgressRingProps {
  /** Completion, 0–100. */
  value: number;
  size?: number;
  /** Ring thickness in px. */
  stroke?: number;
  color?: string;
  track?: string;
  /** Centred content — the app puts the dose count here. */
  children?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

/** Circular progress. Used on the home screen for "doses taken today". */
export function ProgressRing({
  value,
  size = 64,
  stroke = 6,
  color = 'var(--primary)',
  track = 'var(--surface-container-highest)',
  children,
  className,
  style,
}: ProgressRingProps) {
  const r = (size - stroke) / 2;
  const circumference = 2 * Math.PI * r;

  return (
    <div
      className={className}
      role="progressbar"
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={100}
      style={{ position: 'relative', width: size, height: size, flexShrink: 0, ...style }}
    >
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={track} strokeWidth={stroke} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - value / 100)}
          style={{ transition: 'stroke-dashoffset .6s cubic-bezier(.2,0,0,1)' }}
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexDirection: 'column',
        }}
      >
        {children}
      </div>
    </div>
  );
}
