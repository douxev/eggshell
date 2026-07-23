/**
 * Eggshell design system — the Material 3 expressive language behind the
 * Transition app, as React components.
 *
 * Import the stylesheet once, then wrap the tree in <EggshellProvider>:
 *
 *   import '@eggshell/design-system/styles.css';
 */

export { EggshellProvider } from './components/provider';
export type { EggshellProviderProps } from './components/provider';

export { Icon } from './components/icon';
export type { IconProps } from './components/icon';

export { EggshellLogo } from './components/logo';
export type { EggshellLogoProps } from './components/logo';

export { Card } from './components/card';
export type { CardProps, CardVariant } from './components/card';

export { Button } from './components/button';
export type { ButtonProps, ButtonSize, ButtonVariant } from './components/button';

export { IconButton } from './components/icon-button';
export type { IconButtonProps } from './components/icon-button';

export { Fab } from './components/fab';
export type { FabColor, FabProps } from './components/fab';

export { Chip } from './components/chip';
export type { ChipProps } from './components/chip';

export { Switch } from './components/switch';
export type { SwitchProps } from './components/switch';

export { Segmented } from './components/segmented';
export type { SegmentedOption, SegmentedProps } from './components/segmented';

export { Slider } from './components/slider';
export type { SliderProps } from './components/slider';

export { ProgressRing } from './components/progress-ring';
export type { ProgressRingProps } from './components/progress-ring';

export { SectionTitle } from './components/section-title';
export type { SectionTitleProps } from './components/section-title';

export { Placeholder } from './components/placeholder';
export type { PlaceholderProps } from './components/placeholder';

export { ScreenHeader } from './components/screen-header';
export type { ScreenHeaderProps } from './components/screen-header';

export { ListRow } from './components/list-row';
export type { ListRowProps } from './components/list-row';

export { NavBar } from './components/nav-bar';
export type { NavBarProps, NavItem } from './components/nav-bar';

export { ThemeSwatchCard } from './components/theme-swatch-card';
export type { ThemeSwatchCardProps } from './components/theme-swatch-card';

export { MoodBars } from './components/mood-bars';
export type { MoodBarsProps } from './components/mood-bars';

export { GestureNav, PhoneFrame, StatusBar } from './components/phone-frame';
export type { PhoneFrameProps } from './components/phone-frame';

export { THEMES } from './themes';
export type { EggshellTheme, EggshellThemeId } from './themes';
