import { ProgressRing } from '@eggshell/design-system';

export const DoseProgress = () => (
  <ProgressRing value={50} size={80} stroke={7}>
    <span className="t-title-l" style={{ color: 'var(--primary)' }}>
      1<span style={{ color: 'var(--on-surface-variant)', fontSize: 15 }}>/2</span>
    </span>
    <span className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>
      PRISES
    </span>
  </ProgressRing>
);

export const Steps = () => (
  <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
    {[0, 25, 50, 75, 100].map((v) => (
      <ProgressRing key={v} value={v} size={56} stroke={6}>
        <span className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>
          {v}%
        </span>
      </ProgressRing>
    ))}
  </div>
);

export const Colors = () => (
  <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
    <ProgressRing value={70} size={64} />
    <ProgressRing value={70} size={64} color="var(--tertiary)" />
    <ProgressRing value={70} size={64} color="var(--success)" />
    <ProgressRing value={70} size={64} color="var(--error)" />
  </div>
);
