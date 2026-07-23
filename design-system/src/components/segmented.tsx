import type { CSSProperties } from 'react';
import { Icon } from './icon';

export interface SegmentedOption {
  value: string;
  label: string;
}

export interface SegmentedProps {
  options: Array<SegmentedOption | string>;
  value: string;
  onChange: (next: string) => void;
  /**
   * `joined` is the Material 3 segmented button — one outlined track, dividers
   * between segments. `pills` is the looser pair the photo screen uses: a
   * filled pill for the active view, outlined pills for the rest.
   */
  variant?: 'joined' | 'pills';
  className?: string;
  style?: CSSProperties;
}

const normalise = (o: SegmentedOption | string): SegmentedOption =>
  typeof o === 'string' ? { value: o, label: o } : o;

/** Switches between mutually exclusive views. */
export function Segmented({ options, value, onChange, variant = 'joined', className, style }: SegmentedProps) {
  const items = options.map(normalise);

  if (variant === 'pills') {
    return (
      <div className={className} style={{ display: 'flex', gap: 10, ...style }}>
        {items.map((o) => {
          const selected = o.value === value;
          return (
            <button
              key={o.value}
              onClick={() => onChange(o.value)}
              aria-pressed={selected}
              style={{
                height: 40,
                borderRadius: 100,
                padding: '0 20px',
                fontSize: 14.5,
                fontWeight: 600,
                background: selected ? 'var(--secondary-container)' : 'transparent',
                color: selected ? 'var(--on-secondary-container)' : 'var(--on-surface-variant)',
                border: selected ? 'none' : '1px solid var(--outline)',
                transition: 'background .15s',
              }}
            >
              {o.label}
            </button>
          );
        })}
      </div>
    );
  }

  return (
    <div
      className={className}
      style={{
        display: 'flex',
        border: '1px solid var(--outline)',
        borderRadius: 100,
        overflow: 'hidden',
        ...style,
      }}
    >
      {items.map((o, i) => {
        const selected = o.value === value;
        return (
          <button
            key={o.value}
            onClick={() => onChange(o.value)}
            aria-pressed={selected}
            style={{
              flex: 1,
              height: 40,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
              background: selected ? 'var(--secondary-container)' : 'transparent',
              color: selected ? 'var(--on-secondary-container)' : 'var(--on-surface)',
              fontSize: 13.5,
              fontWeight: 600,
              borderLeft: i ? '1px solid var(--outline)' : 'none',
              transition: 'background .15s',
            }}
          >
            {selected ? <Icon name="check" size={16} /> : null}
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
