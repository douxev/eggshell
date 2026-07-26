import SwiftUI
import UIKit

// The furniture the three Médics forms share: the pushed-screen chrome, the
// labelled block, the field, the chip species a form uses, the stepper row and
// the colour swatches.
//
// Everything reads its colours from `\.palette` — the app ships 14 palettes —
// and nothing carries meaning by colour alone: a selected chip gains a check, a
// chosen swatch gains a ring *and* an accessible label.

/// Pushed-screen chrome of §4 on iOS: inline title, « ‹ Retour » on the left.
///
/// The written word is deliberate. Accueil hides its navigation bar, so the
/// system back item has no previous title to inherit and would draw as a bare
/// chevron — a 44 pt target with nothing to read on it.
struct MedsScreenChrome: ViewModifier {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    let title: String

    func body(content: Content) -> some View {
        content
            .background(palette.surface.ignoresSafeArea())
            .tint(palette.primary)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 15, weight: .semibold))
                            Text("Retour")
                                .font(.system(size: 16))
                        }
                        .foregroundStyle(palette.primary)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Retour")
                }
            }
    }
}

extension View {
    /// Applies the Médics pushed-screen chrome. A screen that also needs a bar
    /// action adds its own `.toolbar { … }` — toolbars compose.
    func medsScreen(_ title: String) -> some View {
        modifier(MedsScreenChrome(title: title))
    }
}

/// One labelled block of a form: the small-caps label, the fields, and an
/// optional sentence underneath that says what the block is for.
///
/// The title arrives already uppercase: a translation must be able to opt out
/// of small caps, so no text transform is applied.
struct MedsFormBlock<Content: View>: View {
    @Environment(\.palette) private var palette

    let title: String
    var footnote: String? = nil
    var spacing: CGFloat = Spacing.m
    @ViewBuilder var content: () -> Content

    init(
        _ title: String,
        footnote: String? = nil,
        spacing: CGFloat = Spacing.m,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.footnote = footnote
        self.spacing = spacing
        self.content = content
    }

    var body: some View {
        EggCard(variant: .low, paddingH: 18, paddingV: 16, spacing: spacing) {
            MicroLabel(title)
            content()
            if let footnote {
                Text(footnote)
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// A form field: the field container of §3.4 (radius 16) around a native
/// `TextField`, so the three forms don't each invent their own.
struct MedsField: View {
    @Environment(\.palette) private var palette

    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var multiline: Bool = false
    /// Hard cap the path downstream expects (alias 40, reminder text 60). Held
    /// here rather than at save time so the field can never *look* like it kept
    /// what it will silently drop.
    var maxLength: Int? = nil

    var body: some View {
        field
            .font(.eggBody)
            .foregroundStyle(palette.onSurface)
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, 11)
            .frame(minHeight: Metrics.touchTarget, alignment: .leading)
            .background(
                palette.surfaceContainerHigh,
                in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
            .onChange(of: text) { _, value in
                if let maxLength, value.count > maxLength {
                    text = String(value.prefix(maxLength))
                }
            }
    }

    @ViewBuilder
    private var field: some View {
        if multiline {
            TextField(placeholder, text: $text, axis: .vertical)
                .lineLimit(3...6)
        } else {
            TextField(placeholder, text: $text)
                .keyboardType(keyboard)
        }
    }
}

/// The chip species a **form** uses: radius 10 with a leading check, not the
/// radius-100 pill of a period (D4 keeps the two apart on purpose, so a choice
/// never reads as a period). 36 tall, inside a full 44 pt target.
struct MedsChoiceChip: View {
    @Environment(\.palette) private var palette

    let label: String
    var selected: Bool
    /// The option the app proposes (the injection-site rotation). It wears a
    /// star *and* is named in the sentence above the row — never colour alone.
    var suggested: Bool = false
    let action: () -> Void

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                } else if suggested {
                    Image(systemName: "star.fill")
                        .font(.system(size: 11, weight: .bold))
                }
                Text(label)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .padding(.leading, selected || suggested ? 12 : 16)
            .padding(.trailing, 16)
            .frame(height: 36)
            .background(
                shape.fill(selected ? palette.secondaryContainer : palette.surfaceContainerLowest))
            .overlay { if !selected { shape.stroke(palette.outlineVariant, lineWidth: 1) } }
            // The chip stays 36 tall; the target around it is a full 44 (§10).
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(suggested && !selected ? "\(label), suggéré" : label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// A wrapping row of form chips over catalogue identifiers.
struct MedsChipRow: View {
    let options: [String]
    let selected: String?
    let label: (String) -> String
    var suggested: String? = nil
    let onSelect: (String) -> Void

    var body: some View {
        ChipFlowLayout(spacing: Spacing.s, lineSpacing: Spacing.xs) {
            ForEach(options, id: \.self) { option in
                MedsChoiceChip(
                    label: label(option),
                    selected: option == selected,
                    suggested: option == suggested,
                    action: { onSelect(option) })
            }
        }
    }
}

/// Label + typed value + stepper. The keyboard stays available: nobody wants to
/// tap the stepper 168 times to say « une fois par semaine ».
struct MedsStepperRow: View {
    @Environment(\.palette) private var palette

    let label: String
    @Binding var value: Int
    let range: ClosedRange<Int>

    var body: some View {
        HStack(spacing: Spacing.s) {
            Text(label)
                .font(.eggCallout)
                .foregroundStyle(palette.onSurface)
            Spacer(minLength: Spacing.s)
            TextField(label, value: $value, format: .number)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .font(EggFont.titleS)
                .foregroundStyle(palette.onSurface)
                .frame(width: 62)
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, 7)
                .background(
                    palette.surfaceContainerHigh,
                    in: RoundedRectangle(cornerRadius: Radius.iconTile, style: .continuous))
                .labelsHidden()
                // Only the ceiling is clamped live: clamping the floor too
                // would fight the keyboard the moment the field goes empty.
                // The floor is enforced where the cadence is built.
                .onChange(of: value) { _, v in
                    if v > range.upperBound { value = range.upperBound }
                }
            Stepper(label, value: $value, in: range)
                .labelsHidden()
        }
        .frame(minHeight: Metrics.touchTarget)
    }
}

/// The accent colour of a treatment: the ten presets, « aucune », and a free
/// colour. The presets are the same ten Android offers, so the swatch you
/// picked renders identically on both phones from the shared vault.
struct MedColorPicker: View {
    @Environment(\.palette) private var palette

    /// Opaque ARGB (0xFFRRGGBB), or nil for « aucune ».
    @Binding var argb: Int64?

    private let diameter: CGFloat = Metrics.touchTarget

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            ChipFlowLayout(spacing: Spacing.s, lineSpacing: Spacing.s) {
                Button { argb = nil } label: {
                    ZStack {
                        Circle().fill(palette.surfaceContainerHighest)
                        Text("∅")
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .frame(width: diameter, height: diameter)
                    .overlay { ring(selected: argb == nil) }
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Aucune couleur")
                .accessibilityAddTraits(argb == nil ? [.isSelected] : [])

                ForEach(Array(MedSwatch.all.enumerated()), id: \.element) { pair in
                    Button { argb = pair.element } label: {
                        Circle()
                            .fill(MedColor.color(fromArgb: pair.element))
                            .frame(width: diameter, height: diameter)
                            .overlay { ring(selected: argb == pair.element) }
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    // A swatch has no name a translation could carry, so the
                    // accessible label numbers them — the colour itself is your
                    // own choice, not a meaning the app assigns.
                    .accessibilityLabel("Couleur \(pair.offset + 1)")
                    .accessibilityAddTraits(argb == pair.element ? [.isSelected] : [])
                }
            }

            ColorPicker(selection: custom, supportsOpacity: false) {
                Text("Une autre couleur")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurface)
            }
            .frame(minHeight: Metrics.touchTarget)
        }
    }

    /// The free colour reads the current choice and writes it back as ARGB, so
    /// the swatches and the wheel are two views of one value.
    private var custom: Binding<Color> {
        Binding(
            get: { argb.map { MedColor.color(fromArgb: $0) } ?? palette.primary },
            set: { argb = MedColor.argb(from: $0) })
    }

    @ViewBuilder
    private func ring(selected: Bool) -> some View {
        Circle()
            .stroke(
                selected ? palette.primary : palette.outlineVariant,
                lineWidth: selected ? 3 : 1)
    }
}
