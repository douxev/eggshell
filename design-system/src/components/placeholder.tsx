import type { CSSProperties } from 'react';

export interface PlaceholderProps {
  /** Caption shown in the middle, e.g. the image's role. */
  label?: string;
  /** CSS aspect ratio, e.g. `'1 / 1'` or `'3 / 4'`. */
  ratio?: string;
  radius?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Striped stand-in for imagery. Progress photos are private, so designs use
 * this rather than shipping a stock body shot.
 */
export function Placeholder({ label, ratio = '1 / 1', radius = 16, className, style }: PlaceholderProps) {
  return (
    <div
      className={className}
      style={{
        aspectRatio: ratio,
        borderRadius: radius,
        position: 'relative',
        overflow: 'hidden',
        background:
          'repeating-linear-gradient(45deg, var(--surface-container-high) 0 10px, var(--surface-container) 10px 20px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        ...style,
      }}
    >
      {label ? (
        <span
          style={{
            fontFamily: 'ui-monospace, monospace',
            fontSize: 11,
            color: 'var(--on-surface-variant)',
            background: 'var(--surface)',
            padding: '3px 8px',
            borderRadius: 6,
            opacity: 0.9,
          }}
        >
          {label}
        </span>
      ) : null}
    </div>
  );
}
