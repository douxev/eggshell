import type { CSSProperties, ReactNode } from 'react';
import { Icon } from './icon';

export interface ChipProps {
  children?: ReactNode;
  /** Selected chips swap the leading glyph for a checkmark. */
  selected?: boolean;
  /** Material Symbols name shown when not selected. */
  icon?: string;
  /** Gives an unselected chip a surface fill instead of an outline. */
  elevated?: boolean;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
}

/** Filter chip. The app uses these for dose routes, ranges and tags. */
export function Chip({ children, selected, icon, elevated, onClick, className, style }: ChipProps) {
  return (
    <button
      onClick={onClick}
      aria-pressed={selected}
      className={className}
      style={{
        height: 36,
        borderRadius: 10,
        padding: icon || selected ? '0 16px 0 12px' : '0 16px',
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        border: selected ? 'none' : '1px solid var(--outline-variant)',
        background: selected
          ? 'var(--secondary-container)'
          : elevated
            ? 'var(--surface-container-low)'
            : 'transparent',
        color: selected ? 'var(--on-secondary-container)' : 'var(--on-surface-variant)',
        fontSize: 14,
        fontWeight: 600,
        letterSpacing: '.1px',
        whiteSpace: 'nowrap',
        transition: 'background .14s, border .14s',
        ...style,
      }}
    >
      {selected ? <Icon name="check" size={18} /> : icon ? <Icon name={icon} size={18} /> : null}
      {children}
    </button>
  );
}
