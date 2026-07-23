import type { CSSProperties } from 'react';
import { Icon } from './icon';

export interface SwitchProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  /** Accessible name — required when there is no visible label beside it. */
  label?: string;
  className?: string;
  style?: CSSProperties;
}

/** Material 3 switch. The thumb grows and gains a checkmark when on. */
export function Switch({ checked, onChange, disabled, label, className, style }: SwitchProps) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={className}
      style={{
        width: 52,
        height: 32,
        borderRadius: 100,
        position: 'relative',
        flexShrink: 0,
        background: checked ? 'var(--primary)' : 'var(--surface-container-highest)',
        border: checked ? 'none' : '2px solid var(--outline)',
        transition: 'background .2s',
        opacity: disabled ? 0.4 : 1,
        cursor: disabled ? 'not-allowed' : 'pointer',
        ...style,
      }}
    >
      <span
        style={{
          position: 'absolute',
          top: '50%',
          left: checked ? 24 : 6,
          width: checked ? 24 : 16,
          height: checked ? 24 : 16,
          borderRadius: 100,
          transform: 'translateY(-50%)',
          transition: 'all .2s cubic-bezier(.2,0,0,1)',
          background: checked ? 'var(--on-primary)' : 'var(--outline)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {checked ? <Icon name="check" size={14} style={{ color: 'var(--primary)' }} /> : null}
      </span>
    </button>
  );
}
