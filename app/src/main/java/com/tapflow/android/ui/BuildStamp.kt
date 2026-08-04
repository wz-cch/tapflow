package com.tapflow.android.ui

import com.tapflow.android.BuildConfig

/**
 * Which build this is, in a form a person can check at a glance.
 *
 * `0.1.0+8f6ab6f · 2026-08-04 07:52`
 *
 * The timestamp is the part that earns its place. A sha identifies a build exactly and tells a human
 * nothing — working out that `411089d` was a week old meant opening the repository, so "is this the build
 * you were just sent" could not be answered by either side and a round of debugging went into asking it
 * instead. Everybody already knows when the APK arrived, so a date settles it without looking anything up.
 *
 * One function because three places show this — the home screen's title, the version line at the bottom,
 * and the header on every copied report — and a build stamp that disagrees with itself is worse than none.
 */
fun buildStamp(): String = "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}"
