package com.commacompliance.archiver.util

/**
 * True when any of [values] is null, empty, or whitespace-only. Collapses the
 * repeated `a.isBlank() || b.isBlank() || ...` field-presence guards in the
 * enroll/registration/discovery response parsers into one readable check.
 */
fun anyBlank(vararg values: String?): Boolean = values.any { it.isNullOrBlank() }
