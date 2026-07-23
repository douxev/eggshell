import { EggshellProvider, GestureNav } from '@eggshell/design-system';

const shell: React.CSSProperties = {
  width: 380,
  borderRadius: 22,
  overflow: 'hidden',
  background: 'var(--surface)',
  border: '1px solid var(--outline-variant)',
  paddingTop: 26,
};

export const OnLightSurface = () => (
  <div style={shell}>
    <GestureNav />
  </div>
);

export const OnDarkSurface = () => (
  <EggshellProvider mode="dark" fill={false}>
    <div style={shell}>
      <GestureNav />
    </div>
  </EggshellProvider>
);
