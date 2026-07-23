import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Icon } from './icon';

export interface IconButtonProps {
  /** Material Symbols name. */
  name: string;
  size?: number;
  /** Force the solid glyph. Selected buttons fill automatically. */
  fill?: boolean;
  /** Gives the button a surface tint so it reads as a control on a plain background. */
  tonal?: boolean;
  /** Active state — primary container background and a filled glyph. */
  selected?: boolean;
  /** Tooltip and accessible name. */
  title?: string;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
}

/** 44px circular icon-only button — the trailing action on every app bar. */
export function IconButton({
  name,
  size = 24,
  fill = false,
  tonal,
  selected,
  title,
  onClick,
  className,
  style,
}: IconButtonProps) {
  const [pressed, setPressed] = useState(false);

  return (
    <button
      title={title}
      aria-label={title ?? name}
      aria-pressed={selected}
      onClick={onClick}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerLeave={() => setPressed(false)}
      className={className}
      style={{
        width: 44,
        height: 44,
        borderRadius: 100,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
        background: selected
          ? 'var(--primary-container)'
          : tonal
            ? 'var(--surface-container-highest)'
            : 'transparent',
        color: selected ? 'var(--on-primary-container)' : 'var(--on-surface-variant)',
        transition: 'background .15s, transform .1s',
        transform: pressed ? 'scale(.9)' : 'none',
        ...style,
      }}
    >
      <Icon name={name} size={size} fill={fill || selected} />
    </button>
  );
}
