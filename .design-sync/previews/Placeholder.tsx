import { Placeholder } from '@eggshell/design-system';

export const Gallery = () => (
  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12, maxWidth: 380 }}>
    <div>
      <Placeholder label="30 mai" ratio="3 / 4" radius={20} />
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
        <span className="t-title-s">30 mai</span>
        <span className="t-label-s" style={{ color: 'var(--primary)' }}>
          MAI 26
        </span>
      </div>
    </div>
    <div>
      <Placeholder label="14 août" ratio="3 / 4" radius={20} />
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
        <span className="t-title-s">14 août</span>
        <span className="t-label-s" style={{ color: 'var(--primary)' }}>
          AOÛT 26
        </span>
      </div>
    </div>
  </div>
);

export const Ratios = () => (
  <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', maxWidth: 440 }}>
    <Placeholder label="1:1" ratio="1 / 1" style={{ width: 120 }} />
    <Placeholder label="3:4" ratio="3 / 4" style={{ width: 120 }} />
    <Placeholder label="16:9" ratio="16 / 9" style={{ width: 160 }} />
  </div>
);

export const Unlabelled = () => (
  <div style={{ display: 'flex', gap: 12, maxWidth: 380 }}>
    <Placeholder ratio="1 / 1" radius={100} style={{ width: 72 }} />
    <Placeholder ratio="1 / 1" radius={20} style={{ width: 72 }} />
    <Placeholder ratio="1 / 1" radius={8} style={{ width: 72 }} />
  </div>
);
