// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.Context
import android.os.Build
import android.system.Os
import app.solstone.core.pl.JournalVersionReading
import app.solstone.core.pl.aboutBlock
import app.solstone.core.pl.journalDisplayLine
import app.solstone.core.pl.nativeArchForAbout
import app.solstone.core.pl.renderLine

data class AndroidAboutFacts(
    val versionName: String?,
    val build: String?,
    val osVersion: String?,
    val arch: String?,
)

fun readAndroidAboutFacts(context: Context): AndroidAboutFacts {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName?.takeIf(String::isNotBlank)
    val build = packageInfo?.let { info ->
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString()
            else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        }.getOrNull()
    }
    val release = Build.VERSION.RELEASE.orEmpty()
    val osVersion = release.takeIf { it.matches(Regex("^[0-9]+(\\.[0-9]+)*$")) }
    val machine = runCatching { Os.uname().machine }.getOrNull()
    return AndroidAboutFacts(
        versionName = versionName,
        build = build,
        osVersion = osVersion,
        arch = nativeArchForAbout(machine),
    )
}

fun phoneAboutBlock(
    facts: AndroidAboutFacts,
    reading: JournalVersionReading?,
    now: Long,
): String = aboutBlock(
    appLine = renderLine(
        name = "android app",
        version = facts.versionName.orEmpty(),
        build = facts.build,
        os = "android",
        osVersion = facts.osVersion.orEmpty(),
        arch = facts.arch.orEmpty(),
    ),
    journalLine = journalDisplayLine(reading, now),
)
