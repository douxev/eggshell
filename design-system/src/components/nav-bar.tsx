import type { CSSProperties } from 'react';
import { Icon } from './icon';

export interface NavItem {
  id: string;
  /** Material Symbols name. */
  icon: string;
  /** Shown when `showLabels` is on; always used as the accessible name. */
  label: string;
}

export interface NavBarProps {
  items: NavItem[];
  /** `id` of the active destination. */
  active: string;
  onNavigate: (id: string) => void;
  /**
   * The shipped app runs icon-only. Turn on for the labelled variant when the
   * design has room for it.
   */
  showLabels?: boolean;
  className?: string;
  style?: CSSProperties;
}

/** The app's six-destination bottom bar: a pill and a filled glyph mark the active tab. */
export function NavBar({ items, active, onNavigate, showLabels = false, className, style }: NavBarProps) {
  return (
    <nav
      className={className}
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        height: showLabels ? 84 : 68,
        paddingTop: 10,
        background: 'var(--surface-container)',
        ...style,
      }}
    >
      {items.map((item) => {
        const selected = item.id === active;
        return (
          <button
            key={item.id}
            onClick={() => onNavigate(item.id)}
            aria-label={item.label}
            aria-current={selected ? 'page' : undefined}
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <span
              style={{
                width: 64,
                height: 32,
                borderRadius: 100,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: selected ? 'var(--secondary-container)' : 'transparent',
                color: selected ? 'var(--on-secondary-container)' : 'var(--on-surface-variant)',
                transition: 'background .2s',
              }}
            >
              <Icon name={item.icon} size={24} fill={selected} />
            </span>
            {showLabels ? (
              <span
                style={{
                  fontSize: 12,
                  fontWeight: selected ? 700 : 500,
                  color: selected ? 'var(--on-surface)' : 'var(--on-surface-variant)',
                }}
              >
                {item.label}
              </span>
            ) : null}
          </button>
        );
      })}
    </nav>
  );
}
