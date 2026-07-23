import { useState } from 'react';
import { Switch } from '@eggshell/design-system';

const Row = ({ label, hint, initial }: { label: string; hint?: string; initial: boolean }) => {
  const [on, setOn] = useState(initial);
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 20 }}>
      <div>
        <div className="t-title-s">{label}</div>
        {hint ? (
          <div className="t-body-s" style={{ color: 'var(--on-surface-variant)' }}>
            {hint}
          </div>
        ) : null}
      </div>
      <Switch checked={on} onChange={setOn} label={label} />
    </div>
  );
};

export const States = () => (
  <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
    <Switch checked onChange={() => {}} label="Activé" />
    <Switch checked={false} onChange={() => {}} label="Désactivé" />
    <Switch checked disabled onChange={() => {}} label="Activé, verrouillé" />
  </div>
);

export const SettingsRows = () => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 18, maxWidth: 380 }}>
    <Row label="Déverrouillage biométrique" hint="Empreinte ou visage" initial />
    <Row label="Mode leurre" hint="Affiche un écran neutre" initial={false} />
    <Row label="Rappels" hint="Notifications de prise" initial />
  </div>
);
