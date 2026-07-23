import { Icon } from '@eggshell/design-system';

const grid: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(6, 1fr)',
  gap: 18,
  maxWidth: 420,
  textAlign: 'center',
  color: 'var(--on-surface-variant)',
};

const NAMES = [
  'home',
  'medication',
  'edit_note',
  'show_chart',
  'photo_camera',
  'graphic_eq',
  'science',
  'vaccines',
  'sanitizer',
  'mood',
  'notifications',
  'encrypted',
];

export const AppIcons = () => (
  <div style={grid}>
    {NAMES.map((n) => (
      <div key={n} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
        <Icon name={n} size={26} />
        <span style={{ fontSize: 9, opacity: 0.7, wordBreak: 'break-all' }}>{n}</span>
      </div>
    ))}
  </div>
);

export const Sizes = () => (
  <div style={{ display: 'flex', alignItems: 'flex-end', gap: 18, color: 'var(--on-surface)' }}>
    {[16, 20, 24, 32, 48].map((s) => (
      <Icon key={s} name="medication" size={s} />
    ))}
  </div>
);

export const OutlinedVsFilled = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 24, color: 'var(--primary)' }}>
    <Icon name="favorite" size={36} />
    <Icon name="favorite" size={36} fill />
    <Icon name="lock" size={36} />
    <Icon name="lock" size={36} fill />
  </div>
);

export const Weights = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 20, color: 'var(--on-surface)' }}>
    {[200, 400, 600, 700].map((w) => (
      <Icon key={w} name="check" size={34} weight={w} />
    ))}
  </div>
);
