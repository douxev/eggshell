import { useState } from 'react';
import { Segmented } from '@eggshell/design-system';

export const Joined = () => {
  const [v, setV] = useState('semaine');
  return (
    <div style={{ maxWidth: 380 }}>
      <Segmented
        value={v}
        onChange={setV}
        options={[
          { value: 'semaine', label: 'Semaine' },
          { value: 'mois', label: 'Mois' },
          { value: 'annee', label: 'Année' },
        ]}
      />
    </div>
  );
};

export const Pills = () => {
  const [v, setV] = useState('galerie');
  return (
    <Segmented
      variant="pills"
      value={v}
      onChange={setV}
      options={[
        { value: 'galerie', label: 'Galerie' },
        { value: 'comparer', label: 'Comparer' },
      ]}
    />
  );
};

export const TwoOptions = () => {
  const [v, setV] = useState('rapide');
  return (
    <div style={{ maxWidth: 300 }}>
      <Segmented
        value={v}
        onChange={setV}
        options={[
          { value: 'rapide', label: 'Rapide' },
          { value: 'detaille', label: 'Détaillé' },
        ]}
      />
    </div>
  );
};
