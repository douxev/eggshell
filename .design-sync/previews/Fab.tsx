import { Fab } from '@eggshell/design-system';

const row: React.CSSProperties = { display: 'flex', gap: 20, alignItems: 'center' };

export const Colors = () => (
  <div style={row}>
    <Fab icon="add" color="primary" title="Ajouter" />
    <Fab icon="add_a_photo" color="tertiary" title="Ajouter une photo" />
    <Fab icon="add" color="surface" title="Ajouter" />
  </div>
);

export const Extended = () => (
  <div style={row}>
    <Fab icon="add" label="Nouvelle dose" extended />
    <Fab icon="mood" label="Journal" extended color="tertiary" />
  </div>
);

export const OnSurface = () => (
  <div
    style={{
      position: 'relative',
      width: 300,
      height: 170,
      borderRadius: 24,
      background: 'var(--surface-container-low)',
      overflow: 'hidden',
    }}
  >
    <span
      className="t-body-s"
      style={{ position: 'absolute', top: 16, left: 18, color: 'var(--on-surface-variant)' }}
    >
      Photos d'évolution
    </span>
    <div style={{ position: 'absolute', right: 18, bottom: 18 }}>
      <Fab icon="add_a_photo" color="surface" title="Ajouter une photo" />
    </div>
  </div>
);
