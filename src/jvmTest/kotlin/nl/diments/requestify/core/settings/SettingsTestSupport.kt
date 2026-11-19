package nl.diments.requestify.core.settings

import kotlin.test.assertTrue

/** Changes the settings in a test, which expects the change to apply (the app itself uses [SettingsStore.tryUpdate]). */
fun SettingsStore.setForTest(f: (Settings) -> Settings) = assertTrue(tryUpdate(f), "the settings could not be changed")
