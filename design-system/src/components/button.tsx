import { useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { Icon } from './icon';

export type ButtonVariant = 'filled' | 'tonal' | 'tonalpri' | 'outlined' | 'text' | 'error';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps {
  children?: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Material Symbols name rendered before the label. */
  icon?: string;
  /** Stretches to the container width. */
  full?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
}

const VARIANTS: Record<ButtonVariant, CSSProperties> = {
  filled: { background: 'var(--primary)', color: 'var(--on-primary)' },
  tonal: { background: 'var(--secondary-container)', color: 'var(--on-secondary-container)' },
  tonalpri: { background: 'var(--primary-container)', color: 'var(--on-primary-container)' },
  outlined: { background: 'transparent', color: 'var(--primary)', border: '1px solid var(--outline)' },
  text: { background: 'transparent', color: 'var(--primary)', padding: '0 12px' },
  error: { background: 'var(--error)', color: 'var(--on-error)' },
};

const HEIGHTS: Record<ButtonSize, number> = { sm: 36, md: 44, lg: 56 };

/** Pill-shaped Material 3 button. */
export function Button({
  children,
  variant = 'filled',
  size = 'md',
  icon,
  full,
  disabled,
  onClick,
  className,
  style,
}: ButtonProps) {
  const [pressed, setPressed] = useState(false);

  return (
    <button
      disabled={disabled}
      onClick={onClick}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerLeave={() => setPressed(false)}
      className={`t-label ${className ?? ''}`.trim()}
      style={{
        height: HEIGHTS[size],
        borderRadius: 100,
        padding: '0 24px',
        width: full ? '100%' : 'auto',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        fontSize: size === 'lg' ? 16 : 14,
        fontWeight: 600,
        transition: 'filter .12s, transform .1s',
        transform: pressed && !disabled ? 'scale(.97)' : 'none',
        opacity: disabled ? 0.4 : 1,
        cursor: disabled ? 'not-allowed' : 'pointer',
        ...VARIANTS[variant],
        ...style,
      }}
    >
      {icon ? <Icon name={icon} size={20} /> : null}
      {children}
    </button>
  );
}
