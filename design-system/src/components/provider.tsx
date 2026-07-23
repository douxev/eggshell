import type { CSSProperties, ReactNode } from 'react';
import type { EggshellThemeId } from '../themes';

export interface EggshellProviderProps {
  children?: ReactNode;
  /**
   * One of the palettes the app ships (see `THEMES`). Omit to use the
   * default lavender palette, which follows `mode`.
   */
  theme?: EggshellThemeId;
  /** Light or dark lavender. Ignored when `theme` is set, since each named palette is already light or dark. */
  mode?: 'light' | 'dark';
  /** Paints the surface colour and fills its container. Turn off to place the wrapper inside an existing layout. */
  fill?: boolean;
  className?: string;
  style?: CSSProperties;
}

/**
 * Root wrapper that puts the design tokens on the page.
 *
 * Every component reads its colours from CSS custom properties, so anything
 * rendered outside this wrapper falls back to the browser's defaults and
 * looks unstyled. Wrap the app once, at the top.
 */
export function EggshellProvider({
  children,
  theme,
  mode = 'light',
  fill = true,
  className = '',
  style,
}: EggshellProviderProps) {
  return (
    <div
      className={`eggshell ${className}`.trim()}
      data-theme={theme ? undefined : mode}
      data-eggshell-theme={theme}
      style={{ ...(fill ? { minHeight: '100%' } : null), ...style }}
    >
      {children}
    </div>
  );
}
