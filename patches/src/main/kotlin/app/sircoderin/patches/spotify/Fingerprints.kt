package app.sircoderin.patches.spotify

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string

/**
 * The Home page's state-restore method (obfuscated, `p.xpr.b(Object)` in 9.2.0.2053). It reads the saved row count
 * with `bundle.getInt("viewDataHoldersSize", 0)`.
 *
 * Class and method names are obfuscated, so this keys on the string literal followed by the `getInt` call.
 * The only other method using the literal (`serialize()`) calls `putInt`, so it cannot match.
 */
internal object ViewDataHoldersSizeRestoreFingerprint : Fingerprint(
    filters = listOf(
        string("viewDataHoldersSize"),
        methodCall(
            definingClass = "Landroid/os/BaseBundle;",
            name = "getInt",
            parameters = listOf("Ljava/lang/String;", "I"),
            returnType = "I",
        ),
    ),
)

/**
 * The Home page's `serialize()` (obfuscated, `p.zu00.serialize()`): saves the view state and the loaded row count with
 * `putInt("viewDataHoldersSize", ...)` when the user leaves Home. Keys on the same string literal as above, but on the
 * `putInt` call and the `Bundle` return type, so it cannot match the restore method.
 */
internal object ViewDataHoldersSizeSerializeFingerprint : Fingerprint(
    returnType = "Landroid/os/Bundle;",
    filters = listOf(
        string("viewDataHoldersSize"),
        methodCall(
            definingClass = "Landroid/os/BaseBundle;",
            name = "putInt",
            parameters = listOf("Ljava/lang/String;", "I"),
            returnType = "V",
        ),
    ),
)

/**
 * The Home page constructor (obfuscated, `p.zu00.<init>`): finds the `evo_swipe_refresh_layout` SwipeRefreshLayout and
 * enables it (and its refresh listener) only when a remote flag is on. Keys on the unobfuscated androidx calls.
 */
internal object HomePageConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;",
            name = "setEnabled",
            parameters = listOf("Z"),
            returnType = "V",
        ),
        methodCall(
            definingClass = "Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;",
            name = "setProgressBackgroundColorSchemeResource",
            parameters = listOf("I"),
            returnType = "V",
        ),
        methodCall(
            definingClass = "Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;",
            name = "setOnRefreshListener",
        ),
    ),
)

/**
 * The Home loop starter (a lambda `switch` case, obfuscated): logs "EvoLoadableResource :: startLoop", then asks the
 * strategy chooser (`kjc0.t(Z)`) which load strategy the first `LoadHomeStructure` should use.
 * The last match is that chooser call: the first call taking a single boolean after the string.
 */
internal object HomeStartLoopFingerprint : Fingerprint(
    filters = listOf(
        string("EvoLoadableResource :: startLoop"),
        methodCall(parameters = listOf("Z")),
    ),
)

/**
 * The Home image-link carousel's view constructor (obfuscated, `p.q680.<init>`). It creates a `LinkedHashMap` of
 * carousel LayoutManager states keyed by section id, which its LayoutManager (the anonymous
 * `ImageLinkCarouselElementUI$view$1$1`, whose name R8 keeps) saves into on detach and restores from on attach.
 * Keys on that class name.
 */
internal object HomeCarouselConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        newInstance("Ljava/util/LinkedHashMap;"),
        newInstance("Lcom/spotify/home/evopage/element/imagelinks/ImageLinkCarouselElementUI\$view\$1\$1;"),
    ),
)
