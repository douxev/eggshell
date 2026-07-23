import { IconButton } from '@eggshell/design-system';

const row: React.CSSProperties = { display: 'flex', gap: 12, alignItems: 'center' };

export const Plain = () => (
  <div style={row}>
    <IconButton name="settings" title="Réglages" />
    <IconButton name="arrow_back" title="Retour" />
    <IconButton name="close" title="Fermer" />
    <IconButton name="share" title="Partager" />
  </div>
);

export const Tonal = () => (
  <div style={row}>
    <IconButton name="add" title="Ajouter" tonal />
    <IconButton name="delete_forever" title="Supprimer" tonal />
    <IconButton name="visibility_off" title="Masquer" tonal />
  </div>
);

export const Selected = () => (
  <div style={row}>
    <IconButton name="favorite" title="Favori" selected />
    <IconButton name="notifications" title="Rappels activés" selected />
    <IconButton name="notifications" title="Rappels désactivés" />
  </div>
);
