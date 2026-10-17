package com.bobyl.tv.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

internal fun benchmarkCompilationMode(): CompilationMode =
    when (val mode = InstrumentationRegistry.getArguments().getString("bobyl.compilation", "None")) {
        "None" -> CompilationMode.None()
        "Partial" -> CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0)
        else -> error("Unknown compilation mode: $mode")
    }
