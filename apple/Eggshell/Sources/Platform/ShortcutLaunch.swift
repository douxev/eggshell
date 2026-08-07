import SwiftUI
import UIKit

/// Delivers a Home-Screen quick action into the SwiftUI stack.
///
/// This needs UIKit plumbing rather than a SwiftUI modifier because there is no
/// SwiftUI surface for quick actions. On iOS 13+ the *scene* delegate is the
/// one that receives them: with SwiftUI's own scene management in place,
/// `UIApplicationDelegate.application(_:performActionFor:)` is never called, so
/// an app-delegate-only implementation compiles, looks right, and silently does
/// nothing. Hence a scene delegate, installed through the app delegate's scene
/// configuration.
///
/// Two arrival paths, and both are needed:
///
///  - **Cold launch** — the action is in `connectionOptions` before any view
///    exists. It is parked in `pending` and drained once the shell appears,
///    because posting to a NotificationCenter nobody is listening to yet would
///    drop it.
///  - **Warm launch** — the app is already running and the action arrives as a
///    callback; the shell is listening, so it is posted straight through.
///
/// Nothing here bypasses the lock. `AppShell` only exists once the vault is
/// open — `AppRootView` shows the unlock screen otherwise — so a quick action
/// tapped on a locked phone lands on the unlock screen exactly as the app icon
/// would, and the module opens only after a real unlock.
final class ShortcutSceneDelegate: NSObject, UIWindowSceneDelegate {

    /// A cold-launch action, held until the shell is on screen to receive it.
    @MainActor static var pending: AppModule?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let item = connectionOptions.shortcutItem,
              let module = ModuleShortcuts.module(for: item) else { return }
        Task { @MainActor in ShortcutSceneDelegate.pending = module }
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        guard let module = ModuleShortcuts.module(for: shortcutItem) else {
            completionHandler(false)
            return
        }
        NotificationCenter.default.post(name: .openModuleShortcut, object: module)
        completionHandler(true)
    }
}

/// Exists only to hand SwiftUI a scene configuration that names
/// [ShortcutSceneDelegate]. Without this, SwiftUI installs its own scene
/// delegate and the quick action has nowhere to land.
final class EggshellAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(
            name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = ShortcutSceneDelegate.self
        return config
    }
}
