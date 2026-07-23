import { Card, Icon, ProgressRing, SectionTitle } from '@eggshell/design-system';

const grid: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
  gap: 12,
  maxWidth: 480,
};

const Label = ({ children }: { children: React.ReactNode }) => (
  <span className="t-title-s">{children}</span>
);

export const Variants = () => (
  <div style={grid}>
    <Card variant="filled">
      <Label>filled</Label>
    </Card>
    <Card variant="low">
      <Label>low</Label>
    </Card>
    <Card variant="lowest">
      <Label>lowest</Label>
    </Card>
    <Card variant="outlined">
      <Label>outlined</Label>
    </Card>
    <Card variant="elevated">
      <Label>elevated</Label>
    </Card>
    <Card variant="primary">
      <Label>primary</Label>
    </Card>
    <Card variant="tertiary">
      <Label>tertiary</Label>
    </Card>
  </div>
);

export const DoseSummary = () => (
  <div style={{ maxWidth: 420 }}>
    <Card variant="filled">
      <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
        <ProgressRing value={100} size={72} stroke={7}>
          <span className="t-title-l" style={{ color: 'var(--primary)' }}>
            2<span style={{ color: 'var(--on-surface-variant)', fontSize: 15 }}>/2</span>
          </span>
          <span className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>
            PRISES
          </span>
        </ProgressRing>
        <div>
          <div className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>
            PROCHAINE PRISE
          </div>
          <div className="t-headline-s" style={{ color: 'var(--primary)' }}>
            Tout est pris ✓
          </div>
        </div>
      </div>
    </Card>
  </div>
);

export const EmptyState = () => (
  <div style={{ maxWidth: 420 }}>
    <SectionTitle>Rappels</SectionTitle>
    <Card variant="outlined">
      <span className="t-body" style={{ color: 'var(--on-surface-variant)' }}>
        Aucun rappel programmé. Configure-en depuis Réglages → Rappels.
      </span>
    </Card>
  </div>
);

export const Interactive = () => (
  <div style={{ maxWidth: 420 }}>
    <Card variant="primary" onClick={() => {}}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <div className="t-headline-s">Comment tu te sens ?</div>
          <div className="t-body">Glisse 4 curseurs · 10 secondes</div>
        </div>
        <div
          style={{
            width: 56,
            height: 56,
            borderRadius: 100,
            flexShrink: 0,
            background: 'var(--tertiary-container)',
            color: 'var(--on-tertiary-container)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Icon name="mood" size={30} fill />
        </div>
      </div>
    </Card>
  </div>
);
