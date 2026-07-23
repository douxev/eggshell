import type { CSSProperties, ReactNode } from 'react';

export interface SectionTitleProps {
  children?: ReactNode;
  /** Optional trailing text button, e.g. "Tout voir". */
  action?: string;
  onAction?: () => void;
  className?: string;
  style?: CSSProperties;
}

/** Small muted label above a group of cards ("Ton ressenti", "Rappels", "Historique"). */
export function SectionTitle({ children, action, onAction, className, style }: SectionTitleProps) {
  return (
    <div
      className={className}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 4px',
        marginBottom: 4,
        ...style,
      }}
    >
      <span className="t-title-s" style={{ color: 'var(--on-surface-variant)' }}>
        {children}
      </span>
      {action ? (
        <button onClick={onAction} className="t-label" style={{ color: 'var(--primary)', fontSize: 13 }}>
          {action}
        </button>
      ) : null}
    </div>
  );
}
