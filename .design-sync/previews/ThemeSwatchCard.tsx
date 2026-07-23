import { useState } from 'react';
import { THEMES, ThemeSwatchCard } from '@eggshell/design-system';

const grid: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
  gap: 20,
  maxWidth: 460,
};

export const Picker = () => {
  const [selected, setSelected] = useState('gruvbox_dark');
  const shown = ['lavender_dark', 'catppuccin_latte', 'gruvbox_dark', 'tokyo_night'];
  return (
    <div style={grid}>
      {THEMES.filter((t) => shown.includes(t.id)).map((t) => (
        <ThemeSwatchCard
          key={t.id}
          themeId={t.id}
          label={t.label}
          selected={selected === t.id}
          onSelect={setSelected}
        />
      ))}
    </div>
  );
};

export const LightPalettes = () => (
  <div style={grid}>
    {THEMES.filter((t) => !t.isDark).map((t) => (
      <ThemeSwatchCard key={t.id} themeId={t.id} label={t.label} selected={t.id === 'solarized_light'} />
    ))}
  </div>
);

export const Single = () => (
  <div style={{ width: 220 }}>
    <ThemeSwatchCard themeId="dracula" label="Dracula" selected />
  </div>
);
