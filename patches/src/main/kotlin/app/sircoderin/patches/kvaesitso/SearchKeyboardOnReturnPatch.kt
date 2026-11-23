package app.sircoderin.patches.kvaesitso

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.sircoderin.patches.shared.Constants.COMPATIBILITY_KVAESITSO
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SCAFFOLD_STATE = "Lde/mm20/launcher2/ui/launcher/scaffold/LauncherScaffoldState;"
private const val SCAFFOLD_CONFIG = "Lde/mm20/launcher2/ui/launcher/scaffold/ScaffoldConfiguration;"
private const val SCAFFOLD_COMPONENT = "Lde/mm20/launcher2/ui/launcher/scaffold/components/ScaffoldComponent;"
private const val SEARCH_COMPONENT = "Lde/mm20/launcher2/ui/launcher/scaffold/components/SearchComponent;"

/**
 * When the search window comes back after more than 5 s in the background (e.g. you launched an app from
 * it, used it, went Home and swiped down again), Kvaesitso calls `LauncherScaffoldState.reset()`. That sets
 * `isSearchBarFocused = false`, so the search bar clears focus and hides the keyboard. On the home screen the
 * search is opened again later, which refocuses it, but in search-only (assistant) mode the search *is* the
 * home page, so nothing turns focus back on: the window stays open with no cursor and no keyboard.
 *
 * Right after `reset()` clears the focus, this runs the home component's `onPreActivate` when that component
 * is the search. That is Kvaesitso's own "search opened" hook, which focuses the search bar only if the
 * "open keyboard" setting is on.
 */
@Suppress("unused")
val searchKeyboardOnReturnPatch = patch(
    name = "Keep keyboard on reopen",
    description = "Keeps the search focused with the keyboard open when the search overlay is reopened " +
        "after a while in another app, instead of showing it without the keyboard.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_KVAESITSO)

    execute {
        LauncherScaffoldStateResetFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val unfocusIndex = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((it as ReferenceInstruction).reference as MethodReference).let { ref ->
                        ref.definingClass == SCAFFOLD_STATE && ref.name == "setSearchBarFocused"
                    }
            }
            check(unfocusIndex >= 0) { "setSearchBarFocused call not found in LauncherScaffoldState.reset" }
            val nextIndex = unfocusIndex + 1

            // p1 (the continuation) and v2 are both overwritten before their next read, so they are free here.
            addInstructionsWithLabels(
                nextIndex,
                """
                    iget-object p1, p0, $SCAFFOLD_STATE->config:$SCAFFOLD_CONFIG
                    invoke-virtual { p1 }, $SCAFFOLD_CONFIG->getHomeComponent()$SCAFFOLD_COMPONENT
                    move-result-object p1
                    instance-of v2, p1, $SEARCH_COMPONENT
                    if-eqz v2, :not_search
                    invoke-virtual { p1, p0 }, $SCAFFOLD_COMPONENT->onPreActivate($SCAFFOLD_STATE)V
                """,
                ExternalLabel("not_search", instructions[nextIndex]),
            )
        }
    }
}
