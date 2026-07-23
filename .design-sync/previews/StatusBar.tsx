import { EggshellProvider, StatusBar } from '@eggshell/design-system';

const shell: React.CSSProperties = {
  width: 380,
  borderRadius: 22,
  overflow: 'hidden',
  background: 'var(--surface)',
  border: '1px solid var(--outline-variant)',
  paddingBottom: 14,
};

export const OnLightSurface = () => (
  <div style={shell}>
    <StatusBar />
  </div>
);

export const OnDarkSurface = () => (
  <EggshellProvider mode="dark" fill={false}>
    <div style={shell}>
      <StatusBar />
    </div>
  </EggshellProvider>
);

export const CustomTime = () => (
  <div style={shell}>
    <StatusBar time="21:00" />
  </div>
);
