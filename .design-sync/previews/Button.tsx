import { Button } from '@eggshell/design-system';

const row: React.CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' };

export const Variants = () => (
  <div style={row}>
    <Button variant="filled">Enregistrer</Button>
    <Button variant="tonal">Plus tard</Button>
    <Button variant="tonalpri">Prendre la dose</Button>
    <Button variant="outlined">Modifier</Button>
    <Button variant="text">Annuler</Button>
    <Button variant="error">Supprimer</Button>
  </div>
);

export const Sizes = () => (
  <div style={row}>
    <Button size="sm">Petit</Button>
    <Button size="md">Moyen</Button>
    <Button size="lg">Grand</Button>
  </div>
);

export const WithIcon = () => (
  <div style={row}>
    <Button icon="check" variant="filled">
      Marquer comme prise
    </Button>
    <Button icon="add" variant="tonal">
      Ajouter un médicament
    </Button>
    <Button icon="picture_as_pdf" variant="outlined">
      Exporter en PDF
    </Button>
  </div>
);

export const FullWidthAndDisabled = () => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 320 }}>
    <Button full size="lg" icon="lock">
      Déverrouiller
    </Button>
    <Button full variant="tonal" disabled>
      Aucune dose programmée
    </Button>
  </div>
);
