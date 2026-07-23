import { useState } from 'react';
import { Card, Slider } from '@eggshell/design-system';

export const Default = () => {
  const [v, setV] = useState(62);
  return (
    <div style={{ maxWidth: 380 }}>
      <Slider value={v} onChange={setV} label="Humeur" />
    </div>
  );
};

const AXES = [
  { label: 'Humeur', accent: 'var(--primary)', initial: 72 },
  { label: 'Dysphorie', accent: 'var(--tertiary)', initial: 30 },
  { label: 'Libido', accent: 'var(--primary-fixed-dim)', initial: 55 },
  { label: 'Énergie', accent: 'var(--secondary)', initial: 48 },
];

const Axis = ({ label, accent, initial }: { label: string; accent: string; initial: number }) => {
  const [v, setV] = useState(initial);
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
        <span className="t-title-s">{label}</span>
        <span className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>
          {v}
        </span>
      </div>
      <Slider value={v} onChange={setV} accent={accent} label={label} />
    </div>
  );
};

export const JournalAxes = () => (
  <div style={{ maxWidth: 400 }}>
    <Card variant="low">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {AXES.map((a) => (
          <Axis key={a.label} {...a} />
        ))}
      </div>
    </Card>
  </div>
);

export const Extremes = () => {
  const [a, setA] = useState(0);
  const [b, setB] = useState(100);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 380 }}>
      <Slider value={a} onChange={setA} label="Minimum" />
      <Slider value={b} onChange={setB} label="Maximum" accent="var(--tertiary)" />
    </div>
  );
};
