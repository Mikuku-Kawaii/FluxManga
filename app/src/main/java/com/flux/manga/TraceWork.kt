package com.flux.manga

import android.os.Trace
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Synchronous work only: sections must begin and end on the same thread. */
internal inline fun <T> traceWork(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}

/** Measurement attribution only; preserves the child's constraints, size and placement. */
internal fun Modifier.traceMeasure(name: String): Modifier = layout { measurable, constraints ->
    val child = traceWork("FluxMeasure:$name") { measurable.measure(constraints) }
    layout(child.width, child.height) { child.placeRelative(0, 0) }
}
