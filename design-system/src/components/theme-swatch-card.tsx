import type { CSSProperties } from 'react';
import { Icon } from './icon';
import { EggshellLogo } from './logo';

export interface ThemeSwatchCardProps {
  /** Palette id, e.g. `gruvbox_dark`. Drives the preview colours. */
  themeId: string;
  /** Human label shown under the card. */
  label: string;
  selected?: boolean;
  onSelect?: (themeId: string) => void;
  className?: string;
  style?: CSSProperties;
}

/**
 * One cell of the theme picker: a miniature of the palette (three accent
 * swatches over two text bars) with its name underneath, marked by a small
 * egg that fills in when the palette is active.
 *
 * The preview paints itself by scoping the palette's own attribute to the
 * card, so it always shows the real colours rather than a copy of them.
 */
export function ThemeSwatchCard({ themeId, label, selected, onSelect, className, style }: ThemeSwatchCardProps) {
  return (
    <div
      className={className}
      style={{ display: 'flex', flexDirection: 'column', gap: 8, ...style }}
    >
      <button
        onClick={() => onSelect?.(themeId)}
        aria-pressed={selected}
        aria-label={label}
        data-eggshell-theme={themeId}
        style={{
          height: 150,
          borderRadius: 20,
          padding: 18,
          background: 'var(--surface)',
          border: '1px solid var(--outline-variant)',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          textAlign: 'left',
          overflow: 'hidden',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ width: 30, height: 30, borderRadius: 100, background: 'var(--primary)' }} />
          <span style={{ width: 22, height: 22, borderRadius: 100, background: 'var(--secondary)' }} />
          <span style={{ width: 18, height: 18, borderRadius: 100, background: 'var(--tertiary)' }} />
          {selected ? (
            <span
              style={{
                marginLeft: 'auto',
                width: 28,
                height: 28,
                borderRadius: 100,
                background: 'var(--primary)',
                color: 'var(--on-primary)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Icon name="check" size={18} />
            </span>
          ) : null}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
          <span style={{ height: 6, borderRadius: 100, background: 'var(--on-surface-variant)', opacity: 0.7 }} />
          <span
            style={{ height: 6, width: '62%', borderRadius: 100, background: 'var(--on-surface-variant)', opacity: 0.45 }}
          />
        </div>
      </button>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingLeft: 2 }}>
        <EggshellLogo variant="mark" size={12} title="" style={{ opacity: selected ? 1 : 0.45 }} />
        <span
          className="t-title-s"
          style={{ color: selected ? 'var(--primary)' : 'var(--on-surface)', fontWeight: selected ? 700 : 500 }}
        >
          {label}
        </span>
      </div>
    </div>
  );
}
