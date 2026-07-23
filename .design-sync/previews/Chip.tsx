import { Chip } from '@eggshell/design-system';

const row: React.CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center', maxWidth: 420 };

export const Routes = () => (
  <div style={row}>
    <Chip selected>Gel</Chip>
    <Chip>Patch</Chip>
    <Chip>Comprimé</Chip>
    <Chip>Injection</Chip>
  </div>
);

export const WithIcons = () => (
  <div style={row}>
    <Chip icon="science">Analyses</Chip>
    <Chip icon="mood">Ressenti</Chip>
    <Chip icon="medication">Doses</Chip>
  </div>
);

export const Elevated = () => (
  <div style={row}>
    <Chip elevated icon="calendar_month">
      7 jours
    </Chip>
    <Chip elevated icon="calendar_month">
      30 jours
    </Chip>
    <Chip selected>1 an</Chip>
  </div>
);
