import SwiftUI

// The brand mark, drawn rather than bundled so it stays crisp at any size and
// needs no asset catalogue entry.
//
// The egg is the ONE thing a theme never re-tints (handoff §11): it is painted
// from `Brand`, never from `\.palette`. Geometry is the design system's
// `EggshellLogo`, kept in its own units (a 238 × 312 egg, optionally sitting in
// a 512 × 512 rounded shell) and scaled at draw time.

/// The egg silhouette, in the mark's own 238 × 312 design units.
private struct EggBody: Shape {
    static let designWidth: CGFloat = 238
    static let designHeight: CGFloat = 312

    func path(in rect: CGRect) -> Path {
        let s = min(rect.width / Self.designWidth, rect.height / Self.designHeight)
        let ox = rect.minX + (rect.width - Self.designWidth * s) / 2
        let oy = rect.minY + (rect.height - Self.designHeight * s) / 2
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: ox + x * s, y: oy + y * s)
        }

        var path = Path()
        path.move(to: p(119, 0))
        path.addCurve(to: p(238, 176), control1: p(168, 0), control2: p(238, 92))
        path.addCurve(to: p(119, 312), control1: p(238, 251), control2: p(185, 312))
        path.addCurve(to: p(0, 176), control1: p(53, 312), control2: p(0, 251))
        path.addCurve(to: p(119, 0), control1: p(0, 92), control2: p(70, 0))
        path.closeSubpath()
        return path
    }
}

struct EggshellLogo: View {
    /// `icon` is the app-icon lockup (egg on a rounded shell tile); `mark` is
    /// the bare egg, for when the surface behind it already reads as a card.
    enum Variant { case icon, mark }

    var size: CGFloat = 74
    var variant: Variant = .icon
    /// Decorative uses (next to a wordmark that already says it) pass false.
    var labelled: Bool = true

    var body: some View {
        content
            .frame(width: size, height: size)
            .accessibilityHidden(!labelled)
            .accessibilityLabel(labelled ? Text("eggshell") : Text(""))
    }

    @ViewBuilder
    private var content: some View {
        switch variant {
        case .icon:
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: size * 0.215, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Brand.shellBright, Brand.shellDim],
                            startPoint: .top, endPoint: .bottom)
                    )
                // The egg sits at (137, 100) of the 512 icon canvas.
                egg(width: size * EggBody.designWidth / 512)
                    .offset(x: size * 137 / 512, y: size * 100 / 512)
            }
        case .mark:
            // Fit the taller axis: the egg is portrait, the frame is square.
            egg(width: size * EggBody.designWidth / EggBody.designHeight)
                .frame(width: size, height: size)
        }
    }

    private func egg(width: CGFloat) -> some View {
        let s = width / EggBody.designWidth
        return ZStack(alignment: .topLeading) {
            EggBody()
                .fill(
                    LinearGradient(
                        gradient: Gradient(stops: [
                            .init(color: Brand.egg, location: 0.45),
                            .init(color: Brand.eggShade, location: 1),
                        ]),
                        startPoint: .topLeading, endPoint: .bottomTrailing)
                )
            // The shine: an ellipse centred on (58, 74), tilted back 18°.
            Ellipse()
                .fill(Brand.eggHighlight)
                .frame(width: 48 * s, height: 76 * s)
                .rotationEffect(.degrees(-18))
                .offset(x: 34 * s, y: 36 * s)
        }
        .frame(width: width, height: width * EggBody.designHeight / EggBody.designWidth)
    }
}
