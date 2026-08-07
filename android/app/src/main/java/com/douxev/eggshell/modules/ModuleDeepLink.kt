package com.douxev.eggshell.modules

import android.content.Context
import android.content.Intent
import com.douxev.eggshell.MainActivity

/**
 * How a launcher shortcut or a module widget asks the app to open a module.
 *
 * Both surfaces build the same Intent through here so there is one shape to
 * reason about, and one place where the guarantees below are stated.
 *
 * **The link is a request, never a bypass.** It is stashed and replayed only
 * once the vault is genuinely open — `AppRootViewModel` decides the route
 * before the pending link is ever read, so a shortcut tapped on a locked phone
 * lands on the unlock screen exactly as the app icon would. There is no path
 * from an Intent extra to vault contents.
 *
 * `MainActivity` is exported, so any installed app can send it an Intent with
 * whatever extras it likes. The two things that keeps honest: the module id is
 * resolved against [AppModule.fromId] and an unknown one is dropped, and the
 * destination is an ordinary in-app screen behind the same lock as the rest.
 * The worst an outsider achieves is opening the app on a screen the user could
 * have reached in two taps — after unlocking it themselves.
 */
object ModuleDeepLink {

    /** Open the module's own screen. */
    const val ACTION_OPEN = "com.douxev.eggshell.action.OPEN_MODULE"

    /** Open the module straight on its capture screen ("+ note", "+ ressenti"). */
    const val ACTION_ADD = "com.douxev.eggshell.action.ADD_IN_MODULE"

    const val EXTRA_MODULE = "module"

    fun openIntent(context: Context, module: AppModule): Intent =
        intentFor(context, module, ACTION_OPEN)

    fun addIntent(context: Context, module: AppModule): Intent =
        intentFor(context, module, ACTION_ADD)

    private fun intentFor(context: Context, module: AppModule, action: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_MODULE, module.id)
            // NEW_TASK so a launcher can start it from outside a task; CLEAR_TOP
            // so tapping a second shortcut re-targets the running activity
            // instead of stacking a second copy of the app behind the first.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /** The module an incoming intent names, or null when it names none we know. */
    fun moduleOf(intent: Intent?): AppModule? {
        val action = intent?.action ?: return null
        if (action != ACTION_OPEN && action != ACTION_ADD) return null
        return AppModule.fromId(intent.getStringExtra(EXTRA_MODULE))
    }

    /** True when the intent asks for the module's capture screen. */
    fun wantsAdd(intent: Intent?): Boolean = intent?.action == ACTION_ADD
}
