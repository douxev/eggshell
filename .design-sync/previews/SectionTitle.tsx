import { Card, SectionTitle } from '@eggshell/design-system';

export const Plain = () => (
  <div style={{ maxWidth: 400 }}>
    <SectionTitle>Ton ressenti</SectionTitle>
    <Card variant="low">
      <span className="t-body" style={{ color: 'var(--on-surface-variant)' }}>
        Dernière entrée il y a 2 heures
      </span>
    </Card>
  </div>
);

export const WithAction = () => (
  <div style={{ maxWidth: 400 }}>
    <SectionTitle action="Tout voir">Historique</SectionTitle>
    <Card variant="low">
      <span className="t-body" style={{ color: 'var(--on-surface-variant)' }}>
        14 entrées ce mois-ci
      </span>
    </Card>
  </div>
);

export const Stacked = () => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 400 }}>
    <div>
      <SectionTitle>Rappels</SectionTitle>
      <Card variant="low">
        <span className="t-body">Deux rappels actifs</span>
      </Card>
    </div>
    <div>
      <SectionTitle action="Gérer">Médications</SectionTitle>
      <Card variant="low">
        <span className="t-body">Estrodose · Concerta</span>
      </Card>
    </div>
  </div>
);
