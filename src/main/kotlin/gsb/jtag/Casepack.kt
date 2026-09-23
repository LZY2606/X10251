package gsb.jtag

import java.io.File

/** Loads the offline case pack (domain fixtures, not generic tables). */
object Casepack {

    fun loadEdges(file: File, src: String): List<SampleEdge> =
        file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",").map { it.trim() }
            SampleEdge(
                id = c[0], ord = c[1].toInt(),
                t = Quant.parse(c[2], basis = "t0=trigger"),
                tms = c[3].toInt(), tdi = c[4].toInt(),
                tdo = c[5].toIntOrNull(),
                decodedState = c[6].ifEmpty { null },
                src = src
            )
        }

    fun loadResets(file: File): List<ResetEvent> =
        file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",").map { it.trim() }
            ResetEvent(c[0], c[1].toInt(), ResetEvent.Kind.valueOf(c[2]), c[3])
        }

    fun loadIrCandidates(file: File): List<IrLengthCandidate> =
        file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",").map { it.trim() }
            IrLengthCandidate(c[0], c[1].toInt(), c[2].toInt(), c[3])
        }

    fun loadBoard(file: File): BoardRevision {
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines[0].split(",").map { it.trim() }
        return BoardRevision(head[0], head[1], lines.drop(1).map { it.trim() })
    }

    fun loadBsdl(file: File, id: String, device: String, version: String): BsdlMap =
        Bsdl.parse(id, device, version, file.readText())

    fun resourceDir(): File {
        val url = Casepack::class.java.classLoader.getResource("casepack")
            ?: throw IllegalStateException("casepack not on classpath")
        return File(url.toURI())
    }
}
