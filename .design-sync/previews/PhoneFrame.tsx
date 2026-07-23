import {
  Card,
  EggshellProvider,
  Fab,
  Icon,
  NavBar,
  PhoneFrame,
  ProgressRing,
  ScreenHeader,
  SectionTitle,
} from '@eggshell/design-system';

const ITEMS = [
  { id: 'today', icon: 'home', label: 'Accueil' },
  { id: 'med', icon: 'medication', label: 'Médics' },
  { id: 'journal', icon: 'edit_note', label: 'Journal' },
  { id: 'labs', icon: 'show_chart', label: 'Courbes' },
  { id: 'photos', icon: 'photo_camera', label: 'Photos' },
  { id: 'voice', icon: 'graphic_eq', label: 'Voix' },
];

const HomeScreen = () => (
  <>
    <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', padding: '4px 20px 0' }}>
      <ScreenHeader title="Bonjour" subtitle="Samedi 30 mai" action="settings" actionTitle="Réglages" />

      <Card variant="filled" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 18 }}>
          <ProgressRing value={100} size={72} stroke={7}>
            <span className="t-title-l" style={{ color: 'var(--primary)' }}>
              0<span style={{ color: 'var(--on-surface-variant)', fontSize: 15 }}>/0</span>
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

      <SectionTitle>Ton ressenti</SectionTitle>
      <Card variant="filled" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14 }}>
          <div>
            <div className="t-headline-s" style={{ color: 'var(--primary)' }}>
              Comment tu te sens ?
            </div>
            <div className="t-body-s" style={{ color: 'var(--on-surface-variant)' }}>
              Glisse 4 curseurs · 10 secondes
            </div>
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

      <SectionTitle>Rappels</SectionTitle>
      <Card variant="outlined">
        <span className="t-body" style={{ color: 'var(--on-surface-variant)' }}>
          Aucun rappel programmé. Configure-en depuis Réglages → Rappels.
        </span>
      </Card>

      <div style={{ position: 'absolute', right: 20, bottom: 96 }}>
        <Fab icon="add" color="surface" title="Ajouter" />
      </div>
    </div>
    <NavBar items={ITEMS} active="today" onNavigate={() => {}} />
  </>
);

export const HomeDark = () => (
  <EggshellProvider mode="dark" fill={false}>
    <PhoneFrame>
      <HomeScreen />
    </PhoneFrame>
  </EggshellProvider>
);
