import type { CSSProperties, ReactNode } from 'react';
import { IconButton } from './icon-button';

export interface ScreenHeaderProps {
  title: ReactNode;
  /** Small secondary line under the title, e.g. the date on the home screen. */
  subtitle?: ReactNode;
  /**
   * `large` is a top-level destination ("Médications"). `small` pairs with a
   * back arrow on a pushed screen ("Thème").
   */
  size?: 'large' | 'small';
  /** Renders a leading back arrow. */
  onBack?: () => void;
  /** Material Symbols name for the trailing action — usually `settings`. */
  action?: string;
  onAction?: () => void;
  actionTitle?: string;
  className?: string;
  style?: CSSProperties;
}

/** The title block at the top of every screen. */
export function ScreenHeader({
  title,
  subtitle,
  size = 'large',
  onBack,
  action,
  onAction,
  actionTitle,
  className,
  style,
}: ScreenHeaderProps) {
  const large = size === 'large';

  return (
    <div
      className={className}
      style={{
        display: 'flex',
        alignItems: large ? 'flex-start' : 'center',
        gap: 12,
        padding: large ? '8px 4px 16px' : '8px 4px',
        ...style,
      }}
    >
      {onBack ? <IconButton name="arrow_back" title="Retour" onClick={onBack} style={{ marginLeft: -8 }} /> : null}

      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          className={large ? 't-display-s' : 't-headline'}
          style={{ color: 'var(--on-surface)', fontWeight: large ? 400 : 500 }}
        >
          {title}
        </div>
        {subtitle ? (
          <div className="t-body" style={{ color: 'var(--on-surface-variant)', marginTop: 2 }}>
            {subtitle}
          </div>
        ) : null}
      </div>

      {action ? <IconButton name={action} title={actionTitle} onClick={onAction} fill /> : null}
    </div>
  );
}
