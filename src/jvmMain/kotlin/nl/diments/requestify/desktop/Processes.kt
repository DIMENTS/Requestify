package nl.diments.requestify.desktop

import java.io.File

/**
 * A process Requestify starts and then leaves to itself (the crash restart, the update helper): it gets no pipes to
 * this process, which is about to end. Its output goes nowhere and it reads nothing.
 */
fun detachedProcess(command: List<String>): ProcessBuilder = ProcessBuilder(command)
    .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
