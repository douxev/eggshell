import { Icon, IconButton, ListRow, Switch } from '@eggshell/design-system';

const stack: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 460 };

export const Medications = () => (
  <div style={stack}>
    <ListRow icon="medication" title="Concerta" badge="Autre" subtitle="36 mg · Oral" />
    <ListRow icon="sanitizer" title="Estrodose" badge="Œstrogène" subtitle="4 pr · Topique (gel/crème)" />
    <ListRow icon="vaccines" title="Estradiol valérate" badge="Œstrogène" subtitle="10 mg · Injection IM" />
  </div>
);

export const WithTrailing = () => (
  <div style={stack}>
    <ListRow
      icon="notifications"
      title="Rappel du matin"
      subtitle="Tous les jours à 09:00"
      trailing={<Switch checked onChange={() => {}} label="Activer le rappel du matin" />}
    />
    <ListRow
      icon="event_upcoming"
      title="Prise du soir"
      subtitle="Tous les jours à 21:00"
      trailing={<Switch checked={false} onChange={() => {}} label="Activer la prise du soir" />}
    />
  </div>
);

export const WithoutIcon = () => (
  <div style={stack}>
    <ListRow
      title="Endocrinologue"
      subtitle="Jeudi 14 août · 14:30"
      trailing={<IconButton name="chevron_right" title="Ouvrir le rendez-vous" />}
    />
    <ListRow
      title="Prise de sang"
      subtitle="Lundi 1 septembre · 08:15"
      trailing={<IconButton name="chevron_right" title="Ouvrir le rendez-vous" />}
    />
  </div>
);

export const CustomLeading = () => (
  <div style={stack}>
    <ListRow
      title="Œstradiol"
      badge="pmol/L"
      subtitle="412 · dans la cible"
      leading={
        <div
          style={{
            width: 56,
            height: 56,
            borderRadius: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--tertiary-container)',
            color: 'var(--on-tertiary-container)',
          }}
        >
          <Icon name="science" size={26} fill />
        </div>
      }
    />
  </div>
);
