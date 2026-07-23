import { EggshellLogo } from '@eggshell/design-system';

export const AppIcon = () => (
  <div style={{ display: 'flex', gap: 24, alignItems: 'flex-end' }}>
    <EggshellLogo size={128} />
    <EggshellLogo size={72} />
    <EggshellLogo size={48} />
    <EggshellLogo size={32} />
  </div>
);

export const Mark = () => (
  <div style={{ display: 'flex', gap: 24, alignItems: 'flex-end' }}>
    <EggshellLogo variant="mark" size={96} />
    <EggshellLogo variant="mark" size={56} />
    <EggshellLogo variant="mark" size={28} />
    <EggshellLogo variant="mark" size={14} />
  </div>
);

export const Lockup = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
    <EggshellLogo size={64} />
    <div>
      <div className="t-display-s" style={{ color: 'var(--on-surface)' }}>
        Eggshell
      </div>
      <div className="t-body" style={{ color: 'var(--on-surface-variant)' }}>
        Suivi de transition, entièrement sur ton téléphone
      </div>
    </div>
  </div>
);

export const CornerRadius = () => (
  <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
    <EggshellLogo size={72} radius={0.5} />
    <EggshellLogo size={72} radius={0.215} />
    <EggshellLogo size={72} radius={0.08} />
    <EggshellLogo size={72} radius={0} />
  </div>
);
