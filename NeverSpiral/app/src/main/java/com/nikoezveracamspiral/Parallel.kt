package com.nikoezveracamspiral

import java.util.stream.IntStream

/** Runs [body] for 0 until [count] across all CPU cores. The pixel loops split cleanly by row,
 * so this cuts processing time by roughly the number of cores. */
internal inline fun parallelFor(count: Int, crossinline body: (Int) -> Unit) {
    IntStream.range(0, count).parallel().forEach { body(it) }
}
