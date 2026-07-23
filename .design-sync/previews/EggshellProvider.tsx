import { Button, Card, EggshellProvider, ProgressRing, SectionTitle } from '@eggshell/design-system';

const Sample = ({ name }: { name: string }) => (
  <div style={{ padding: 18, borderRadius: 20, background: 'var(--surface)', width: 250 }}>
    <SectionTitle>{name}</SectionTitle>
    <Card variant="filled" padding={16}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <ProgressRing value={66} size={48} stroke={5} />
        <div>
          <div className="t-title-s">Prochaine prise</div>
          <div className="t-body-s" style={{ color: 'var(--on-surface-variant)' }}>
            Aujourd'hui à 21:00
          </div>
        </div>
      </div>
    </Card>
    <div style={{ marginTop: 12 }}>
      <Button size="sm" icon="check">
        Marquer
      </Button>
    </div>
  </div>
);

export const LightAndDark = () => (
  <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
    <EggshellProvider mode="light" fill={false}>
      <Sample name="Lavender · clair" />
    </EggshellProvider>
    <EggshellProvider mode="dark" fill={false}>
      <Sample name="Lavender · sombre" />
    </EggshellProvider>
  </div>
);

export const NamedPalettes = () => (
  <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
    <EggshellProvider theme="catppuccin_latte" fill={false}>
      <Sample name="Catppuccin Latte" />
    </EggshellProvider>
    <EggshellProvider theme="gruvbox_dark" fill={false}>
      <Sample name="Gruvbox sombre" />
    </EggshellProvider>
    <EggshellProvider theme="tokyo_night" fill={false}>
      <Sample name="Tokyo Night" />
    </EggshellProvider>
    <EggshellProvider theme="nord" fill={false}>
      <Sample name="Nord" />
    </EggshellProvider>
  </div>
);
