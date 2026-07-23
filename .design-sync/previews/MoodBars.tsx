import { MoodBars } from '@eggshell/design-system';

const Entry = ({ values, date, text }: { values: number[]; date: string; text: string }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 16,
      padding: 16,
      borderRadius: 28,
      background: 'var(--surface-container-low)',
    }}
  >
    <MoodBars values={values} />
    <div style={{ flex: 1, minWidth: 0 }}>
      <div className="t-title-s">{date}</div>
      <div className="t-body-s" style={{ color: 'var(--on-surface-variant)' }}>
        {text}
      </div>
    </div>
    <span className="t-body-s" style={{ color: 'var(--on-surface-variant)' }}>
      09:52
    </span>
  </div>
);

export const JournalEntries = () => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 420 }}>
    <Entry values={[18, 22]} date="sam. 30 mai" text="Like a Monday…" />
    <Entry values={[92, 78, 85, 64]} date="sam. 30 mai" text="I'm feeling Great!" />
  </div>
);

export const Scale = () => (
  <div style={{ display: 'flex', gap: 28, alignItems: 'flex-end' }}>
    <MoodBars values={[10, 20, 15, 25]} />
    <MoodBars values={[50, 45, 60, 55]} />
    <MoodBars values={[95, 88, 92, 80]} />
  </div>
);

export const Sizes = () => (
  <div style={{ display: 'flex', gap: 28, alignItems: 'flex-end' }}>
    <MoodBars values={[70, 40, 90, 55]} height={28} barWidth={6} />
    <MoodBars values={[70, 40, 90, 55]} height={44} />
    <MoodBars values={[70, 40, 90, 55]} height={64} barWidth={11} />
  </div>
);
