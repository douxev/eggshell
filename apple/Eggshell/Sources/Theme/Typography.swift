import SwiftUI

// Type scale approximating the Android Material 3 "expressive" scale, mapped to
// SF Pro (the native iOS face). Use these instead of raw .font(...) so the look
// stays consistent across screens.
extension Font {
    static let eggDisplay  = Font.system(size: 32, weight: .bold, design: .rounded)
    static let eggTitle    = Font.system(size: 22, weight: .semibold, design: .rounded)
    static let eggHeadline = Font.system(size: 17, weight: .semibold)
    static let eggBody     = Font.system(size: 16, weight: .regular)
    static let eggCallout  = Font.system(size: 15, weight: .regular)
    static let eggLabel    = Font.system(size: 13, weight: .semibold)
    static let eggCaption  = Font.system(size: 12, weight: .regular)
}

enum Spacing {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}

enum Corner {
    static let small: CGFloat = 12
    static let medium: CGFloat = 16
    static let large: CGFloat = 24
}
