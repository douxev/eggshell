import type { CSSProperties } from 'react';

export interface IconProps {
  /**
   * Material Symbols Rounded ligature name, e.g. `medication`, `mood`,
   * `show_chart`. The shipped font is subset to the app's icon set.
   */
  name: string;
  /** Rendered size in px; also drives the font's `opsz` axis. */
  size?: number;
  /** Solid rather than outlined (the `FILL` axis). Selected states use it. */
  fill?: boolean;
  /** Optical weight, 100–700. */
  weight?: number;
  /** Emphasis grade, -50–200. */
  grade?: number;
  className?: string;
  style?: CSSProperties;
}

/** A single Material Symbols Rounded glyph. Inherits `color` from its parent. */
export function Icon({ name, size = 24, fill = false, weight, grade, className = '', style }: IconProps) {
  const axes = [`'FILL' ${fill ? 1 : 0}`];
  if (weight) axes.push(`'wght' ${weight}`);
  if (grade != null) axes.push(`'GRAD' ${grade}`);
  axes.push(`'opsz' ${size}`);

  return (
    <span
      className={`msr ${className}`.trim()}
      style={{ fontSize: size, fontVariationSettings: axes.join(', '), ...style }}
    >
      {name}
    </span>
  );
}
