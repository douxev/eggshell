import type { CSSProperties, ReactNode } from 'react';
import { Icon } from './icon';

export interface ListRowProps {
  title: ReactNode;
  /** Second line — dosage and route on the medication screen. */
  subtitle?: ReactNode;
  /** Material Symbols name for the leading tile. */
  icon?: string;
  /** Small pill beside the title, e.g. "Œstrogène". */
  badge?: string;
  /** Replaces the icon tile entirely (an avatar, a colour swatch, a chart). */
  leading?: ReactNode;
  /** Right-aligned content — a time, a chevron, a switch. */
  trailing?: ReactNode;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
}

/**
 * The app's standard list item: a rounded-square icon tile, a title with an
 * optional badge, and a muted second line.
 */
export function ListRow({
  title,
  subtitle,
  icon,
  badge,
  leading,
  trailing,
  onClick,
  className,
  style,
}: ListRowProps) {
  return (
    <div
      className={className}
      onClick={onClick}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: 16,
        borderRadius: 28,
        background: 'var(--surface-container-low)',
        cursor: onClick ? 'pointer' : 'default',
        ...style,
      }}
    >
      {leading ??
        (icon ? (
          <div
            style={{
              width: 56,
              height: 56,
              borderRadius: 16,
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'var(--primary-container)',
              color: 'var(--on-primary-container)',
            }}
          >
            <Icon name={icon} size={26} fill />
          </div>
        ) : null)}

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span className="t-title-l" style={{ color: 'var(--on-surface)', fontWeight: 600 }}>
            {title}
          </span>
          {badge ? (
            <span
              className="t-label-s"
              style={{
                padding: '3px 10px',
                borderRadius: 100,
                background: 'var(--surface-container-highest)',
                color: 'var(--on-surface-variant)',
                letterSpacing: '.2px',
                whiteSpace: 'nowrap',
              }}
            >
              {badge}
            </span>
          ) : null}
        </div>
        {subtitle ? (
          <div className="t-body" style={{ color: 'var(--on-surface-variant)', marginTop: 2 }}>
            {subtitle}
          </div>
        ) : null}
      </div>

      {trailing ? <div style={{ flexShrink: 0 }}>{trailing}</div> : null}
    </div>
  );
}
