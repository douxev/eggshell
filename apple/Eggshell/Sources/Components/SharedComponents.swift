import SwiftUI
import TransitionCore

// Reusable building blocks introduced for parity with Android: progress ring,
// sparkline, and the dynamic customizable-metric sliders shared by the journal
// and bleeding entry forms.

/// Circular progress ring with a center label. Mirrors android Today ProgressRing.
struct ProgressRing: View {
    @Environment(\.palette) private var palette
    let progress: Double          // 0...1
    var lineWidth: CGFloat = 10
    var size: CGFloat = 84
    var center: String

    var body: some View {
        ZStack {
            Circle()
                .stroke(palette.surfaceContainerHigh, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0, min(1, progress)))
                .stroke(palette.primary, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(center)
                .font(.eggHeadline)
                .foregroundStyle(palette.onSurface)
        }
        .frame(width: size, height: size)
        .animation(.easeInOut, value: progress)
    }
}

/// Minimal line chart for a small series (mood trend, pitch trend, hormone trend).
struct Sparkline: View {
    var values: [Double]
    var tint: Color? = nil
    var height: CGFloat = 40
    @Environment(\.palette) private var palette

    var body: some View {
        GeometryReader { geo in
            let color = tint ?? palette.primary
            if values.count >= 2 {
                let minV = values.min() ?? 0
                let maxV = values.max() ?? 1
                let span = max(maxV - minV, 0.0001)
                let stepX = geo.size.width / CGFloat(values.count - 1)
                Path { p in
                    for (i, v) in values.enumerated() {
                        let x = CGFloat(i) * stepX
                        let y = geo.size.height * (1 - CGFloat((v - minV) / span))
                        if i == 0 { p.move(to: CGPoint(x: x, y: y)) }
                        else { p.addLine(to: CGPoint(x: x, y: y)) }
                    }
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            } else {
                Rectangle().fill(.clear)
            }
        }
        .frame(height: height)
    }
}

/// Renders one slider per enabled MetricDefinition and two-way binds the values
/// (metricId → value). The screen loads the definitions + initial values and
/// persists them via VaultService.replaceMetricValues. Shared by journal +
/// bleeding, mirroring android MetricSlidersColumn.
struct MetricSlidersView: View {
    @Environment(\.palette) private var palette
    let definitions: [MetricDefinition]
    @Binding var values: [Int64: UInt32]

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            ForEach(definitions, id: \.id) { def in
                let v = values[def.id] ?? def.minValue
                let (le, re) = MetricCatalog.emojis(def)
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    HStack {
                        Text(MetricCatalog.displayLabel(def)).font(.eggLabel).foregroundStyle(palette.onSurface)
                        Spacer()
                        Text("\(v)").font(.eggLabel).foregroundStyle(palette.onSurfaceVariant)
                    }
                    HStack(spacing: Spacing.s) {
                        if let le, !le.isEmpty { Text(le) }
                        Slider(
                            value: Binding(
                                get: { Double(values[def.id] ?? def.minValue) },
                                set: { values[def.id] = UInt32($0.rounded()) }),
                            in: Double(def.minValue)...Double(def.maxValue),
                            step: 1)
                        .tint(palette.primary)
                        if let re, !re.isEmpty { Text(re) }
                    }
                }
            }
        }
    }
}

/// Compact French relative due label, mirroring android Today formatRelative:
/// "HH:mm" today, "demain", a weekday within a week, else "d MMM".
func relativeDueLabel(_ ms: Int64, now: Date = Date()) -> String {
    let date = Date(timeIntervalSince1970: Double(ms) / 1000)
    let cal = Calendar.current
    if cal.isDate(date, inSameDayAs: now) {
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "HH:mm"
        return f.string(from: date)
    }
    if let tomorrow = cal.date(byAdding: .day, value: 1, to: now), cal.isDate(date, inSameDayAs: tomorrow) {
        return "demain"
    }
    let days = cal.dateComponents([.day], from: cal.startOfDay(for: now), to: cal.startOfDay(for: date)).day ?? 0
    let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR")
    if days > 1 && days < 7 { f.dateFormat = "EEE"; return f.string(from: date) }
    f.dateFormat = "d MMM"
    return f.string(from: date)
}
