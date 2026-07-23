import { useState } from 'react';
import { NavBar } from '@eggshell/design-system';

const ITEMS = [
  { id: 'today', icon: 'home', label: 'Accueil' },
  { id: 'med', icon: 'medication', label: 'Médics' },
  { id: 'journal', icon: 'edit_note', label: 'Journal' },
  { id: 'labs', icon: 'show_chart', label: 'Courbes' },
  { id: 'photos', icon: 'photo_camera', label: 'Photos' },
  { id: 'voice', icon: 'graphic_eq', label: 'Voix' },
];

const frame: React.CSSProperties = {
  width: 400,
  borderRadius: 20,
  overflow: 'hidden',
  border: '1px solid var(--outline-variant)',
};

export const IconsOnly = () => {
  const [active, setActive] = useState('today');
  return (
    <div style={frame}>
      <NavBar items={ITEMS} active={active} onNavigate={setActive} />
    </div>
  );
};

export const WithLabels = () => {
  const [active, setActive] = useState('journal');
  return (
    <div style={frame}>
      <NavBar items={ITEMS} active={active} onNavigate={setActive} showLabels />
    </div>
  );
};

export const FourDestinations = () => {
  const [active, setActive] = useState('labs');
  return (
    <div style={frame}>
      <NavBar items={ITEMS.slice(0, 4)} active={active} onNavigate={setActive} showLabels />
    </div>
  );
};
