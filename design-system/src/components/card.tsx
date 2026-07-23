import type { CSSProperties, ReactNode } from 'react';

export type CardVariant = 'filled' | 'low' | 'lowest' | 'outlined' | 'elevated' | 'primary' | 'tertiary';

export interface CardProps {
  children?: ReactNode;
  /** Which surface tier the card sits on. */
  variant?: CardVariant;
  /** Corner radius in px. The app uses 24 for content cards and 28 for list rows. */
  radius?: number;
  /** Inner padding in px. */
  padding?: number;
  onClick?: () => void;
  /** Shows the pointer cursor without wiring a handler. */
  interactive?: boolean;
  className?: string;
  style?: CSSProperties;
}

const VARIANTS: Record<CardVariant, CSSProperties> = {
  filled: { background: 'var(--surface-container-high)' },
  low: { background: 'var(--surface-container-low)' },
  lowest: { background: 'var(--surface-container-lowest)' },
  outlined: { background: 'var(--surface)', border: '1px solid var(--outline-variant)' },
  elevated: {
    background: 'var(--surface-container-low)',
    boxShadow: '0 1px 2px rgba(0,0,0,.18), 0 2px 6px 2px rgba(0,0,0,.10)',
  },
  primary: { background: 'var(--primary-container)', color: 'var(--on-primary-container)' },
  tertiary: { background: 'var(--tertiary-container)', color: 'var(--on-tertiary-container)' },
};

/** Rounded surface container. The workhorse of every screen in the app. */
export function Card({
  children,
  variant = 'filled',
  radius = 24,
  padding = 20,
  onClick,
  interactive,
  className,
  style,
}: CardProps) {
  return (
    <div
      className={className}
      onClick={onClick}
      style={{
        borderRadius: radius,
        padding,
        position: 'relative',
        overflow: 'hidden',
        transition: 'transform .12s ease, box-shadow .15s ease',
        cursor: onClick || interactive ? 'pointer' : 'default',
        ...VARIANTS[variant],
        ...style,
      }}
    >
      {children}
    </div>
  );
}
