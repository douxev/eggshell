import { ScreenHeader } from '@eggshell/design-system';

const sheet: React.CSSProperties = {
  background: 'var(--surface)',
  borderRadius: 24,
  padding: '12px 20px',
  maxWidth: 460,
};

export const TopLevel = () => (
  <div style={sheet}>
    <ScreenHeader title="Médications" action="settings" actionTitle="Réglages" />
  </div>
);

export const WithSubtitle = () => (
  <div style={sheet}>
    <ScreenHeader title="Bonjour" subtitle="Samedi 30 mai" action="settings" actionTitle="Réglages" />
  </div>
);

export const SubScreen = () => (
  <div style={sheet}>
    <ScreenHeader size="small" title="Thème" onBack={() => {}} />
  </div>
);

export const SubScreenWithAction = () => (
  <div style={sheet}>
    <ScreenHeader
      size="small"
      title="Photos d'évolution"
      onBack={() => {}}
      action="add_a_photo"
      actionTitle="Ajouter une photo"
    />
  </div>
);
