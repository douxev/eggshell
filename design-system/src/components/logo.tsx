import type { CSSProperties } from 'react';

export interface EggshellLogoProps {
  /** Rendered edge length in px. */
  size?: number;
  /**
   * `icon` is the launcher lockup: the egg on its cream squircle.
   * `mark` is the bare egg, for placing on a surface you control.
   */
  variant?: 'icon' | 'mark';
  /** Corner radius of the squircle, as a fraction of `size`. Ignored by `mark`. */
  radius?: number;
  /** Accessible name. Set to `''` to mark the logo decorative. */
  title?: string;
  className?: string;
  style?: CSSProperties;
}

/**
 * The Eggshell brand mark — a warm egg on cream.
 *
 * Brand colours are deliberately fixed: they come from `--brand-*`, which no
 * palette overrides, so the logo looks the same in every theme.
 */
export function EggshellLogo({
  size = 64,
  variant = 'icon',
  radius = 0.215,
  title = 'Eggshell',
  className,
  style,
}: EggshellLogoProps) {
  const uid = `eggshell-logo-${variant}`;
  const isIcon = variant === 'icon';

  // Egg geometry measured from the shipped 512px launcher icon:
  // 238x312 box, centred, top at y=100.
  const egg = (
    <g transform={isIcon ? 'translate(137 100)' : 'translate(0 0)'}>
      <path
        d="M119 0C168 0 238 92 238 176C238 251 185 312 119 312C53 312 0 251 0 176C0 92 70 0 119 0Z"
        fill={`url(#${uid}-body)`}
      />
      <ellipse cx="58" cy="74" rx="24" ry="38" transform="rotate(-18 58 74)" fill="var(--brand-egg-highlight)" />
    </g>
  );

  return (
    <svg
      width={size}
      height={size}
      viewBox={isIcon ? '0 0 512 512' : '0 0 238 312'}
      xmlns="http://www.w3.org/2000/svg"
      role={title ? 'img' : 'presentation'}
      aria-label={title || undefined}
      aria-hidden={title ? undefined : true}
      className={className}
      style={style}
    >
      {title ? <title>{title}</title> : null}
      <defs>
        <linearGradient id={`${uid}-body`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0.45" stopColor="var(--brand-egg)" />
          <stop offset="1" stopColor="var(--brand-egg-shade)" />
        </linearGradient>
        <linearGradient id={`${uid}-shell`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--brand-shell-bright)" />
          <stop offset="1" stopColor="var(--brand-shell-dim)" />
        </linearGradient>
      </defs>
      {isIcon ? (
        <rect width="512" height="512" rx={512 * radius} ry={512 * radius} fill={`url(#${uid}-shell)`} />
      ) : null}
      {egg}
    </svg>
  );
}
