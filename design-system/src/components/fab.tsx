import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Icon } from './icon';

export type FabColor = 'primary' | 'tertiary' | 'surface';

export interface FabProps {
  /** Material Symbols name. */
  icon: string;
  /** Shown only when `extended`. */
  label?: string;
  /** Widens the button and shows the label beside the glyph. */
  extended?: boolean;
  color?: FabColor;
  title?: string;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
}

const COLORS: Record<FabColor, CSSProperties> = {
  primary: { background: 'var(--primary-container)', color: 'var(--on-primary-container)' },
  tertiary: { background: 'var(--tertiary-container)', color: 'var(--on-tertiary-container)' },
  surface: { background: 'var(--surface-container-high)', color: 'var(--primary)' },
};

/**
 * The floating action button. Note the 18px radius — the app's FAB is a
 * rounded square, not a circle.
 */
export function Fab({ icon, label, extended, color = 'primary', title, onClick, className, style }: FabProps) {
  const [pressed, setPressed] = useState(false);

  return (
    <button
      onClick={onClick}
      title={title ?? label}
      aria-label={title ?? label ?? icon}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerLeave={() => setPressed(false)}
      className={className}
      style={{
        height: 56,
        minWidth: 56,
        borderRadius: 18,
        padding: extended ? '0 20px' : 0,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 10,
        boxShadow: '0 4px 8px 2px rgba(0,0,0,.18), 0 2px 4px rgba(0,0,0,.12)',
        transition: 'transform .12s',
        transform: pressed ? 'scale(.93)' : 'none',
        ...COLORS[color],
        ...style,
      }}
    >
      <Icon name={icon} size={24} fill />
      {extended && label ? (
        <span className="t-label" style={{ fontSize: 15 }}>
          {label}
        </span>
      ) : null}
    </button>
  );
}
