package com.cramtale.core.match

import java.io.File

/** known.txt, names.txt, ignored.txt: one dictionary form per line, '#' starts a comment. Plain files you can edit. */
object KnownListsStore {
    private fun read(f: File): Set<String> = if (!f.exists()) emptySet() else
        f.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    fun load(dir: File) = KnownLists(
        known = read(File(dir, "known.txt")),
        names = read(File(dir, "names.txt")),
        ignored = read(File(dir, "ignored.txt")),
    )

    fun save(dir: File, lists: KnownLists) {
        dir.mkdirs()
        File(dir, "known.txt").writeText(lists.known.sorted().joinToString("\n", postfix = "\n"))
        File(dir, "names.txt").writeText(lists.names.sorted().joinToString("\n", postfix = "\n"))
        File(dir, "ignored.txt").writeText(lists.ignored.sorted().joinToString("\n", postfix = "\n"))
    }
}
