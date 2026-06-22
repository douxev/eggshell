import SwiftUI

// Programmatic tab selection so the Today quick-log can jump to the Photos/Voice
// tabs (which are tab roots, not pushable routes). Mirrors the Android quick-log
// QuickAction.Photo→PHOTOS / Voice→VOICE behaviour.
enum HomeTab: Hashable {
    case today, medications, journal, hormones, bleeding, appointments, photos, voice
}

@MainActor
final class TabRouter: ObservableObject {
    @Published var selection: HomeTab = .today
    func select(_ tab: HomeTab) { selection = tab }
}
