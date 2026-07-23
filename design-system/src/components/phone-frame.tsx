import type { CSSProperties, ReactNode } from 'react';
import { Icon } from './icon';

export interface PhoneFrameProps {
  children?: ReactNode;
  /** Clock shown in the status bar. */
  time?: string;
  /** Hides the status bar and gesture pill for a bare canvas. */
  chrome?: boolean;
  width?: number;
  height?: number;
  className?: string;
  style?: CSSProperties;
}

/** Status bar with clock, camera cutout and the usual indicators. */
export function StatusBar({ time = '9:30' }: { time?: string }) {
  return (
    <div
      style={{
        height: 36,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 22px',
        position: 'relative',
        flexShrink: 0,
        zIndex: 5,
        color: 'var(--on-surface)',
      }}
    >
      <span style={{ fontSize: 14, fontWeight: 600, letterSpacing: '.3px' }}>{time}</span>
      <div
        style={{
          position: 'absolute',
          left: '50%',
          top: 9,
          transform: 'translateX(-50%)',
          width: 11,
          height: 11,
          borderRadius: 100,
          background: '#000',
          opacity: 0.85,
        }}
      />
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Icon name="signal_cellular_alt" size={16} fill />
        <Icon name="wifi" size={16} fill />
        <Icon name="battery_full" size={17} fill style={{ transform: 'rotate(90deg)' }} />
      </div>
    </div>
  );
}

/** The Android gesture pill. */
export function GestureNav() {
  return (
    <div style={{ height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <div style={{ width: 120, height: 4, borderRadius: 2, background: 'var(--on-surface)', opacity: 0.45 }} />
    </div>
  );
}

/**
 * Android device frame for presenting a screen design at true proportions.
 * Use it to mock up a screen; drop it for a component that ships inside one.
 */
export function PhoneFrame({
  children,
  time,
  chrome = true,
  width = 400,
  height = 858,
  className,
  style,
}: PhoneFrameProps) {
  return (
    <div
      className={className}
      style={{
        width,
        height,
        borderRadius: 46,
        padding: 9,
        background: 'var(--frame-border)',
        boxShadow: '0 40px 90px rgba(0,0,0,.38), 0 8px 24px rgba(0,0,0,.22)',
        flexShrink: 0,
        ...style,
      }}
    >
      <div
        style={{
          width: '100%',
          height: '100%',
          borderRadius: 38,
          overflow: 'hidden',
          background: 'var(--surface)',
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        {chrome ? <StatusBar time={time} /> : null}
        <div style={{ flex: 1, minHeight: 0, position: 'relative', display: 'flex', flexDirection: 'column' }}>
          {children}
        </div>
        {chrome ? <GestureNav /> : null}
      </div>
    </div>
  );
}
